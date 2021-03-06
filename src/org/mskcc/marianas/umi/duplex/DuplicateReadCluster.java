/**
 * 
 */
package org.mskcc.marianas.umi.duplex;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.math3.util.FastMath;
import org.mskcc.juber.genotype.Genotype;
import org.mskcc.juber.genotype.GenotypeEventType;
import org.mskcc.juber.genotype.GenotypeID;
import org.mskcc.juber.util.Util;
import org.mskcc.marianas.util.StaticResources;

import gnu.trove.set.hash.TIntHashSet;
import htsjdk.samtools.Cigar;
import htsjdk.samtools.CigarElement;
import htsjdk.samtools.CigarOperator;
import htsjdk.samtools.SAMRecord;
import htsjdk.samtools.reference.IndexedFastaSequenceFile;

/**
 * @author Juber Patel
 * 
 *         class that represents a pileup of reads in a very efficient way
 * 
 */
public class DuplicateReadCluster
{
	private static int debugDels = 0;
	private static int debugOthers = 0;

	private static final IndexedFastaSequenceFile referenceFasta = StaticResources
			.getReference();

	private static final Map<String, Map<Integer, Byte[]>> genotypes = StaticResources
			.getGenotypes();
	private static final String pos = StaticResources.getPositionOfInterest();

	/**
	 * possible base quality values conforming to Sanger and Illumina
	 * sequencing. Not including a few highest and lowest values since those are
	 * treated as special values by some downstream programs.
	 */
	private static final int[] baseQualityRange = new int[] { 5, 90 };
	private static final int maxReadLength = 150;
	private static final int lastPileupIndex = maxReadLength - 1;
	private static final int basesToTrim = 3;

	private final int minMappingQuality;
	private final int minBaseQuality;
	private final int minConsensusPercent;
	private final int deletionMinConsensusPercent;
	private final int insertionMinConsensusPercent;

	private String contig;
	private int startPosition;
	private String UMI;

	// names of reads that are part of this cluster. Mostly for debugging
	// purpose
	private List<String> memberReads;

	// for debug module
	private String contigOfInterest;
	private int positionOfInterest;
	private List<StringBuilder> linesOfInterest;

	private int psReadCount;
	private int nsReadCount;
	// pileup for fragments coming from positive strand
	private PositionPileup[] psPositions;
	// pileup for fragments coming from negative strand
	private PositionPileup[] nsPositions;
	/**
	 * holds special genotypes: multi-base events and insertions
	 * multi-base substitution not handled yet.
	 */
	private Map<GenotypeID, Genotype> psSpecialGenotypes;
	private Map<GenotypeID, Genotype> nsSpecialGenotypes;
	private Map<GenotypeID, int[]> psInsertionQualities;
	private Map<GenotypeID, int[]> nsInsertionQualities;

	private byte[] psConsensus;
	private byte[] nsConsensus;
	private int[] psConsensusQuals;
	private int[] nsConsensusQuals;
	private byte[] consensus;

	private StringBuilder consensusSequenceBuilder;
	private StringBuilder consensusQualityBuilder;

	private byte[] referenceBases;

	private byte[] readBases;
	private byte[] baseQualities;
	private int readIndex;

	private Map<String, Integer> matePositions;

	private static boolean firstTime = true;

	public DuplicateReadCluster(int minMappingQuality, int minBaseQuality,
			int minConsensusPercent)
	{
		this.minMappingQuality = minMappingQuality;
		this.minBaseQuality = minBaseQuality;
		this.minConsensusPercent = minConsensusPercent;
		this.deletionMinConsensusPercent = 50;
		this.insertionMinConsensusPercent = 50;

		this.psPositions = new PositionPileup[maxReadLength];
		this.nsPositions = new PositionPileup[maxReadLength];

		// initialize position pileups
		for (int i = 0; i < psPositions.length; i++)
		{
			psPositions[i] = new PositionPileup(deletionMinConsensusPercent);
			nsPositions[i] = new PositionPileup(deletionMinConsensusPercent);
		}

		// initialize consensii
		this.psConsensus = new byte[maxReadLength];
		this.nsConsensus = new byte[maxReadLength];
		this.consensus = new byte[maxReadLength];
		this.psConsensusQuals = new int[maxReadLength];
		this.nsConsensusQuals = new int[maxReadLength];
		this.consensusSequenceBuilder = new StringBuilder(maxReadLength + 50);
		this.consensusQualityBuilder = new StringBuilder(maxReadLength + 50);

		psSpecialGenotypes = new HashMap<GenotypeID, Genotype>();
		nsSpecialGenotypes = new HashMap<GenotypeID, Genotype>();
		psInsertionQualities = new HashMap<GenotypeID, int[]>();
		nsInsertionQualities = new HashMap<GenotypeID, int[]>();

		memberReads = new ArrayList<String>();

		matePositions = new HashMap<String, Integer>();

		// if running in debug mode
		if (pos != null)
		{
			String[] words = pos.split(":");
			this.contigOfInterest = words[0];
			this.positionOfInterest = Integer.parseInt(words[1].split("-")[0]);
			this.linesOfInterest = new ArrayList<StringBuilder>();
		}
	}

	/**
	 * must call this method before using the region pileup
	 * 
	 * @param interval
	 */
	public void prepareFor(String contig, int startPosition, String UMI)
	{
		this.contig = contig;
		this.startPosition = startPosition;
		this.UMI = UMI;
		this.psReadCount = 0;
		this.nsReadCount = 0;

		int contigLength = referenceFasta.getSequenceDictionary()
				.getSequence(contig).getSequenceLength();
		int endPosition = startPosition + maxReadLength - 1;
		if (endPosition > contigLength)
		{
			endPosition = contigLength;
		}

		referenceBases = referenceFasta
				.getSubsequenceAt(contig, startPosition, endPosition)
				.getBases();

		// clean the pileup for reuse
		for (int i = 0; i < referenceBases.length; i++)
		{
			psPositions[i].reset(referenceBases[i]);
			nsPositions[i].reset(referenceBases[i]);
		}

		// in case referenceBases has shorter length than psPositions and
		// nsPositions.
		// This could happen at the end of the contig (see above adjustment)
		for (int i = referenceBases.length; i < psPositions.length; i++)
		{
			psPositions[i].reset((byte) 'N');
			nsPositions[i].reset((byte) 'N');
		}

		psSpecialGenotypes.clear();
		nsSpecialGenotypes.clear();
		psInsertionQualities.clear();
		nsInsertionQualities.clear();

		memberReads.clear();

		matePositions.clear();
	}

	/**
	 * process the alignment record
	 * 
	 * 
	 * @param record
	 */
	public void add(SAMRecord record, boolean positiveStrand)
	{
		// example of CIGAR string: 146M3I4M5S
		// Assuming that CIGAR will only have these operators: M I D S H

		// TODO HANDLE NEGATIVE STRAND ??? (this is from original RegionPileup)

		PositionPileup[] positions = null;
		Map<GenotypeID, Genotype> specialGenotypes = null;
		Map<GenotypeID, int[]> insertionQualities = null;

		// TODO test fulcrum
		if (record.getMappingQuality() < minMappingQuality
				|| record.getMappingQuality() == 255)
		{
			return;
		}

		memberReads.add(record.getReadName());

		recordMatePosition(record);

		if (positiveStrand)
		{
			psReadCount++;
			positions = psPositions;
			specialGenotypes = psSpecialGenotypes;
			insertionQualities = psInsertionQualities;
		}
		else
		{
			nsReadCount++;
			positions = nsPositions;
			specialGenotypes = nsSpecialGenotypes;
			insertionQualities = nsInsertionQualities;
		}

		readBases = record.getReadBases();
		baseQualities = record.getBaseQualities();

		// tracks the current position in the read
		readIndex = 0;
		// points to the current position in the pileup
		int pileupIndex = record.getStart() - startPosition;
		int operatorLength = 0;
		Cigar cigar = record.getCigar();
		List<CigarElement> elements = cigar.getCigarElements();
		int numCigarElements = elements.size();

		for (int i = 0; i < numCigarElements; i++)
		{
			CigarElement e = elements.get(i);
			CigarOperator operator = e.getOperator();
			operatorLength = e.getLength();

			if (operator.equals(CigarOperator.MATCH_OR_MISMATCH))
			{
				// add the bases
				for (int j = 0; j < operatorLength; j++)
				{
					if (pileupIndex >= 0 && pileupIndex <= lastPileupIndex)
					{
						if (baseQualities[readIndex] >= minBaseQuality)
						{
							positions[pileupIndex].addBase(readBases[readIndex],
									baseQualities[readIndex]);
						}
					}

					// increment both pileup index and read index
					pileupIndex++;
					readIndex++;
				}
			}
			else if (operator.equals(CigarOperator.INSERTION))
			{
				// TODO replace this boundary check with proper tracking of
				// CIGAR and quitting when it goes out of the region
				// ignore insertions at the very beginning and very end of the
				// read
				if (i > 0 && i < numCigarElements - 1)
				{
					// get insertion qualities
					byte[] altQuals = new byte[operatorLength];
					System.arraycopy(baseQualities, readIndex, altQuals, 0,
							operatorLength);
					int q = 0;
					for (int j = 0; j < altQuals.length; j++)
					{
						q += altQuals[j];
					}

					q = q / altQuals.length;

					// record insertion only if average quality is greater than
					// threshold
					if (q >= minBaseQuality)
					{
						// add insertion to special genotypes map
						// make genotype id
						int precedingGenomicPosition = startPosition
								+ (pileupIndex - 1);

						byte[] ref;
						// reference position just to the left of current region
						// i.e. this is an insertion at the beginning of read
						if (pileupIndex == 0)
						{
							ref = referenceFasta
									.getSubsequenceAt(contig,
											precedingGenomicPosition,
											precedingGenomicPosition)
									.getBases();
						}
						else
						{
							ref = new byte[] {
									referenceBases[pileupIndex - 1] };
						}

						byte[] alt = new byte[operatorLength + 1];
						// altQuals does not contain quality for preceding base
						// since that is just reference base,
						// not a base from the read!!!

						alt[0] = ref[0];
						System.arraycopy(readBases, readIndex, alt, 1,
								operatorLength);

						GenotypeID genotypeID = new GenotypeID(
								GenotypeEventType.INSERTION, contig,
								precedingGenomicPosition, ref, alt);

						// debug
						/*
						 * if (contig.equals("8") && startPosition == 55374840
						 * //870
						 * && UMI.equals("ATA+TTC"))
						 * {
						 * int a = 5;
						 * String b = UMI;
						 * System.out.println(cigar + "\t\t"
						 * + record.getReadString() + "\t"
						 * + record.getFirstOfPairFlag());
						 * }
						 */

						// add
						addSpecialGenotype(specialGenotypes, insertionQualities,
								genotypeID, altQuals);
					}
				}

				// increment readIndex but not PileupIndex
				readIndex += operatorLength;
			}
			else if (operator.equals(CigarOperator.DELETION))
			{
				// assign deletion quality as average of preceding and
				// following base qualities
				byte quality;
				if (readIndex == 0)
				{
					quality = baseQualities[readIndex];
				}
				else if (readIndex == baseQualities.length)
				{
					quality = baseQualities[readIndex - 1];
				}
				else
				{
					quality = (byte) ((baseQualities[readIndex - 1]
							+ baseQualities[readIndex]) / 2);
				}

				// skip the entire deletion if it is at the beginning or end of
				// the read or if it does not meet quality criterion
				// just increment the pileupIndex in that case
				if (i == 0 || i == numCigarElements - 1
						|| quality < minBaseQuality)
				{
					pileupIndex += operatorLength;
				}
				else
				{

					// debug
					/*
					 * if (contig.equals("8") && startPosition == 55374840
					 * //870
					 * && UMI.equals("ATA+TTC"))
					 * {
					 * int a = 5;
					 * String b = UMI;
					 * System.out.println(cigar + "\t\t"
					 * + record.getReadString() + "\t"
					 * + record.getFirstOfPairFlag());
					 * }
					 */

					// add deletions to the pileup, one by one
					for (int j = 0; j < operatorLength; j++)
					{
						// don't add deletion if it is outside pileup range
						if (pileupIndex >= 0 && pileupIndex <= lastPileupIndex)
						{
							positions[pileupIndex].addDeletion(quality);
						}

						// increment pileupIndex but don't increment readIndex
						// increment pileupIndex even if you decide not to add
						// deletion due to above conditions
						pileupIndex++;
					}
				}
			}
			else if (operator.equals(CigarOperator.SOFT_CLIP))
			{
				// soft clips do not count in alignment length, no change in
				// pileupIndex

				// but increment the read index since those bases are not
				// clipped from the read
				readIndex += e.getLength();
			}
			else if (operator.equals(CigarOperator.HARD_CLIP))
			{
				// hard clips do not count in alignment length, nor are the
				// clipped bases included in the read sequence
				// so no change in either pileupIndex or readIndex
			}
			else
			{
				// increment both pileupIndex and readIndex properly
				pileupIndex += operatorLength;
				readIndex += operatorLength;
			}
		}
	}

	private void recordMatePosition(SAMRecord record)
	{
		String matePosition = record.getMateReferenceIndex() + "\t"
				+ record.getMateReferenceName() + "\t"
				+ record.getMateAlignmentStart();

		Integer freq = matePositions.get(matePosition);
		if (freq == null)
		{
			matePositions.put(matePosition, 1);
		}
		else
		{
			matePositions.put(matePosition, freq + 1);
		}
	}

	/**
	 * 
	 * increment the coverage count of the given genotype by 1 and
	 * add base qualities to the insertion qualities
	 * 
	 * @param insertionQualities
	 * @param genotypeID
	 */
	private void addSpecialGenotype(Map<GenotypeID, Genotype> specialGenotypes,
			Map<GenotypeID, int[]> insertionQualities, GenotypeID genotypeID,
			byte[] altQuals)
	{
		Genotype genotype = specialGenotypes.get(genotypeID);
		int[] quals = insertionQualities.get(genotypeID);

		if (genotype == null)
		{
			genotype = new Genotype("FromPileup", genotypeID);
			specialGenotypes.put(genotypeID, genotype);

			// also add new quals array
			quals = new int[altQuals.length];
			insertionQualities.put(genotypeID, quals);
		}

		genotype.totalSupportingCoverage++;

		// update quality values in the quals array
		for (int i = 0; i < quals.length; i++)
		{
			quals[i] += altQuals[i];
		}
	}

	/**
	 * merge the other RegionPileup with this one
	 * 
	 * @param other
	 */
	public void merge(DuplicateReadCluster other)
	{
		// TODO: check if this method is necessary.
		// TODO: if yes, make sure it is correct in terms of insertion qaulities
		// etc. !!!

		psReadCount += other.psReadCount;
		nsReadCount += other.nsReadCount;

		memberReads.addAll(other.memberReads);

		// merge position pileups
		for (int i = 0; i < psPositions.length; i++)
		{
			psPositions[i].merge(other.psPositions[i]);
			nsPositions[i].merge(other.nsPositions[i]);
		}

		// merge special genotypes
		for (GenotypeID id : other.psSpecialGenotypes.keySet())
		{
			Genotype otherGenotype = other.psSpecialGenotypes.get(id);
			int[] otherQuals = other.psInsertionQualities.get(id);
			Genotype genotype = psSpecialGenotypes.get(id);
			int[] quals = psInsertionQualities.get(id);
			if (genotype == null)
			{
				psSpecialGenotypes.put(id, otherGenotype);
				psInsertionQualities.put(id, otherQuals);
			}
			else
			{
				genotype.totalSupportingCoverage += otherGenotype.totalSupportingCoverage;
				for (int j = 0; j < quals.length; j++)
				{
					quals[j] += otherQuals[j];
				}

			}
		}

		for (GenotypeID id : other.nsSpecialGenotypes.keySet())
		{
			Genotype otherGenotype = other.nsSpecialGenotypes.get(id);
			int[] otherQuals = other.nsInsertionQualities.get(id);
			Genotype genotype = nsSpecialGenotypes.get(id);
			int[] quals = nsInsertionQualities.get(id);
			if (genotype == null)
			{
				nsSpecialGenotypes.put(id, otherGenotype);
				nsInsertionQualities.put(id, otherQuals);
			}
			else
			{
				genotype.totalSupportingCoverage += otherGenotype.totalSupportingCoverage;
				for (int j = 0; j < quals.length; j++)
				{
					quals[j] += otherQuals[j];
				}

			}
		}
	}

	/**
	 * build consensus sequence from the pileup. This involves building
	 * consensus sequences per strand first and then building the final cluster
	 * consensus sequence.
	 * 
	 * @param positiveStrand
	 *            does the consensus read map on the positive strand or negative
	 *            strand?
	 * @return
	 * @throws IOException
	 */
	/**
	 * @param insertionsWriter
	 * @param positiveStrand
	 * @return
	 * @throws IOException
	 */
	public String collapseMe(BufferedWriter altAlleleWriter,
			BufferedWriter insertionsWriter, boolean positiveStrand)
			throws IOException
	{
		if (firstTime)
		{
			firstTime = false;
			System.out.println("Scheme: Marianas-Original");
		}

		// TODO This method will undergo revision as we determine how exactly we
		// want to build the consensus sequence. There are many parameters to
		// play with.
		///////////////////

		// clear the consensii arrays and StringBuilder
		for (int i = 0; i < psConsensus.length; i++)
		{
			psConsensus[i] = -1;
			psConsensusQuals[i] = 0;
			nsConsensusQuals[i] = 0;
		}

		System.arraycopy(psConsensus, 0, nsConsensus, 0, psConsensus.length);
		System.arraycopy(psConsensus, 0, consensus, 0, psConsensus.length);
		consensusSequenceBuilder.setLength(0);
		consensusQualityBuilder.setLength(0);

		// compute per strand consensus sequence. Right now, just choosing the
		// base with highest count at each position.
		for (int i = 0; i < psPositions.length; i++)
		{
			psConsensus[i] = psPositions[i].getMaxCountBase();
			nsConsensus[i] = nsPositions[i].getMaxCountBase();
		}

		// build family consensus sequence.

		for (int i = 0; i < psPositions.length; i++)
		{
			// find this person's genotype. If not available, use ref base
			Byte[] genotype = null;
			Map<Integer, Byte[]> chrMap = genotypes.get(contig);
			if (chrMap != null)
			{
				genotype = chrMap.get(startPosition + i);
			}

			if (genotype == null || genotype.length == 0)
			{
				genotype = new Byte[] { psPositions[i].getRefBase() };
			}

			// debug
			// if (contig.equals("4") && startPosition + i == 54280706
			// && (psConsensus[i] == 'D' && nsConsensus[i] == 'D'))
			// {
			// int a = 5;
			// }

			finalizeStrandConsensus(psConsensus, psPositions, i, genotype);
			finalizeStrandConsensus(nsConsensus, nsPositions, i, genotype);

			// if same consensii, no problem (including N i.e. -1 i.e. no
			// coverage)
			if (psConsensus[i] == nsConsensus[i])
			{
				consensus[i] = psConsensus[i];
			}
			else if (psConsensus[i] == -1)
			{
				if (isDuplex() && isAlt(nsConsensus[i], genotype))
				{
					// skip
					// duplex alt must be supported on both strands
				}
				else if (isNSSimplex() && !isPositionNSSimplex(i))
				{
					// skip
					// simplex family must also be simplex at the current
					// position to accept consensus
				}
				else
				{
					// accept
					consensus[i] = nsConsensus[i];
				}
			}
			else if (nsConsensus[i] == -1)
			{
				if (isDuplex() && isAlt(psConsensus[i], genotype))
				{
					// skip
					// duplex alt must be supported on both strands
				}
				else if (isPSSimplex() && !isPositionPSSimplex(i))
				{
					// skip
					// simplex family must also be simplex at the current
					// position to accept consensus
				}
				else
				{
					// accept
					consensus[i] = psConsensus[i];
				}
			}
			else
			{
				// in case the two strands have different consensus bases, fall
				// back on genotype.

				// int j;
				// for (j = 0; j < indexes.length; j++)
				// {
				// byte g = genotype[indexes[j]];
				// if (psConsensus[i] == g || nsConsensus[i] == g)
				// {
				// consensus[i] = g;
				// break;
				// }
				// }

				int j;
				for (j = 0; j < genotype.length; j++)
				{
					byte g = genotype[j];
					if (psConsensus[i] == g || nsConsensus[i] == g)
					{
						consensus[i] = g;
						break;
					}
				}

				// could not assign genotype so far, choose the first from
				// the index permutation
				// if (j == indexes.length)
				// {
				// consensus[i] = genotype[indexes[0]];
				// }

				// could not assign genotype so far, make it N
				if (j == genotype.length)
				{
					// consensus[i] = genotype[0];
					consensus[i] = -1;
				}

			}

			// compute base quality
			byte b = consensus[i];
			if (b == -1)
			{
				psConsensusQuals[i] = nsConsensusQuals[i] = baseQualityRange[0];
			}
			else
			{
				psConsensusQuals[i] = psPositions[i].getConsensusQuality(b);
				nsConsensusQuals[i] = nsPositions[i].getConsensusQuality(b);
			}

			// you have the consensus and quality here. If it is non-genotype,
			// write the alt allele info
			if (isAlt(consensus[i], genotype))
			{
				writeAltAlleleInfo(i, genotype, altAlleleWriter);
			}
		}

		// build the consensus sequence and quality
		for (int i = 0; i < consensus.length; i++)
		{
			byte b = consensus[i];
			int quality;
			if (b == -1)
			{
				b = 'N';
				quality = baseQualityRange[0];
			}
			else
			{
				quality = psConsensusQuals[i] + nsConsensusQuals[i];

				// cap at the range boundaries
				if (quality < baseQualityRange[0])
				{
					quality = baseQualityRange[0];
				}
				else if (quality > baseQualityRange[1])
				{
					quality = baseQualityRange[1];
				}
			}

			consensusSequenceBuilder.append((char) b);
			// write printable ascii base quality value
			consensusQualityBuilder.append((char) (quality + 33));
		}

		// Include insertions
		if (isDuplex())
		{
			for (GenotypeID genotypeID : psSpecialGenotypes.keySet())
			{
				if (genotypeID.type != GenotypeEventType.INSERTION)
				{
					continue;
				}

				// debug
				/*
				 * GenotypeID g = new GenotypeID(GenotypeEventType.INSERTION,
				 * "8",
				 * 55374916, new byte[] { 'G' }, new byte[] { 'G', 'T' });
				 * if (genotypeID.equals(g))
				 * {
				 * System.out.println("P-------------------------");
				 * for (GenotypeID id : psSpecialGenotypes.keySet())
				 * {
				 * System.out.println(id);
				 * }
				 * System.out.println("N-------------------------");
				 * for (GenotypeID id : nsSpecialGenotypes.keySet())
				 * {
				 * System.out.println(id);
				 * }
				 * System.out.println("--------------------------");
				 * 
				 * int a = 5;
				 * 
				 * }
				 */

				Genotype positive = psSpecialGenotypes.get(genotypeID);
				Genotype negative = nsSpecialGenotypes.get(genotypeID);

				// must be present on both strands, must have at least
				// minConsensusPercent%
				// support on each strand
				// using 50% instead of minConsensusPercent% for the time being
				// if (negative == null
				// || negative.totalSupportingCoverage < (nsReadCount * 50)
				// / 100
				// || positive.totalSupportingCoverage < (psReadCount * 50)
				// / 100)
				// {
				// continue;
				// }

				if (negative == null || negative.totalSupportingCoverage == 0
						|| positive.totalSupportingCoverage == 0)
				{
					continue;
				}

				double paf = (positive.totalSupportingCoverage * 1.0)
						/ psReadCount;
				double naf = (negative.totalSupportingCoverage * 1.0)
						/ nsReadCount;

				if ((paf * 100) <= insertionMinConsensusPercent
						&& (naf * 100) <= insertionMinConsensusPercent)
				{
					continue;
				}

				// insert the insertion
				int index = genotypeID.position - startPosition + 1;
				char[] bases = new char[genotypeID.alt.length - 1];
				char[] quals = getInsertionConsensusQuals(genotypeID);
				// get the insertion bases, without the ref base
				for (int i = 0; i < bases.length; i++)
				{
					bases[i] = (char) genotypeID.alt[i + 1];
				}

				writeInsertionInfo(index, genotypeID,
						positive.totalSupportingCoverage, psReadCount,
						negative.totalSupportingCoverage, nsReadCount,
						insertionsWriter);
				consensusSequenceBuilder.insert(index, bases);
				consensusQualityBuilder.insert(index, quals);
			}
		}
		else
		{
			// the family is simplex

			Map<GenotypeID, Genotype> specialGenotypes = null;
			int readCount = -1;

			if (psReadCount > 0)
			{
				specialGenotypes = psSpecialGenotypes;
				readCount = psReadCount;
			}
			else
			{
				specialGenotypes = nsSpecialGenotypes;
				readCount = nsReadCount;
			}

			for (GenotypeID genotypeID : specialGenotypes.keySet())
			{
				if (genotypeID.type != GenotypeEventType.INSERTION)
				{
					continue;
				}

				// debug
				/*
				 * GenotypeID g = new GenotypeID(GenotypeEventType.INSERTION,
				 * "8",
				 * 55374916, new byte[] { 'G' }, new byte[] { 'G', 'T' });
				 * if (genotypeID.equals(g) && (UMI.equals("AGA+TTC")
				 * || UMI.equalsIgnoreCase("ATA+TTC")))
				 * {
				 * int a = 5;
				 * 
				 * }
				 */

				// must have at least minConsensusPercent% support on the strand
				// using 50% instead of minConsensusPercent% for the time being
				Genotype genotype = specialGenotypes.get(genotypeID);
				// if (genotype.totalSupportingCoverage < (readCount * 50) /
				// 100)
				// {
				// continue;
				// }

				if (genotype == null || genotype.totalSupportingCoverage == 0)
				{
					continue;
				}

				double af = (genotype.totalSupportingCoverage * 1.0)
						/ readCount;
				if ((af * 100) <= insertionMinConsensusPercent)
				{
					continue;
				}

				// insert the insertion
				int index = genotypeID.position - startPosition + 1;
				char[] bases = new char[genotypeID.alt.length - 1];
				char[] quals = getInsertionConsensusQuals(genotypeID);
				// get the insertion bases, without the ref base
				for (int i = 0; i < bases.length; i++)
				{
					bases[i] = (char) genotypeID.alt[i + 1];
				}

				// write the insertion to the insertions file
				int psSupporting = 0;
				int nsSupporting = 0;
				if (psReadCount > 0)
				{
					psSupporting = genotype.totalSupportingCoverage;
				}
				else
				{
					nsSupporting = genotype.totalSupportingCoverage;
				}

				writeInsertionInfo(index, genotypeID, psSupporting, psReadCount,
						nsSupporting, nsReadCount, insertionsWriter);
				consensusSequenceBuilder.insert(index, bases);
				consensusQualityBuilder.insert(index, quals);
			}
		}

		// trim trailing N's
		int index = consensusSequenceBuilder.length() - 1;
		while (index - basesToTrim > 0)
		{
			if (consensusSequenceBuilder.charAt(index) != 'N'
					&& consensusSequenceBuilder
							.charAt(index - basesToTrim) != 'N')
			{
				break;
			}

			index--;
		}

		// the consensus is all N's
		if (index - basesToTrim == 0)
		{
			return null;
		}

		consensusSequenceBuilder.setLength(index + 1);
		consensusQualityBuilder.setLength(index + 1);

		// trim the leading and trailing n bases to avoid non-genomic bases
		if (consensusSequenceBuilder.length() < 2 * basesToTrim)
		{
			return null;
		}

		consensusSequenceBuilder.delete(0, basesToTrim);
		consensusQualityBuilder.delete(0, basesToTrim);
		int l = consensusSequenceBuilder.length();
		consensusSequenceBuilder.delete(l - basesToTrim, l);
		consensusQualityBuilder.delete(l - basesToTrim, l);

		// remove deletion bases
		l = consensusSequenceBuilder.length();
		StringBuilder seq = new StringBuilder(consensusQualityBuilder.length());
		StringBuilder qual = new StringBuilder(
				consensusSequenceBuilder.length());
		for (int i = 0; i < l; i++)
		{
			char c = consensusSequenceBuilder.charAt(i);
			if (c == 'D')
			{
				continue;
			}

			seq.append(c);
			qual.append(consensusQualityBuilder.charAt(i));
		}

		char[] sequence = seq.toString().toCharArray();
		char[] qualities = qual.toString().toCharArray();

		// if all bases were D.
		// TODO see if there is a better way to write this method.
		if (sequence.length == 0)
		{
			return null;
		}

		// reverse the strand if necessary
		if (!positiveStrand)
		{
			Util.reverseComplement(sequence);
			Util.reverse(qualities);
		}

		StringBuilder collapsedSequenceInfo = new StringBuilder();

		collapsedSequenceInfo.append(contig).append("\t").append(startPosition)
				.append("\t").append(UMI).append("\t").append(psReadCount)
				.append("\t").append(nsReadCount).append("\t")
				.append(getMatePosition()).append("\t").append(sequence)
				.append("\t").append(qualities);

		return collapsedSequenceInfo.toString();
	}

	/**
	 * build consensus sequence from the pileup. This involves building
	 * consensus sequences per strand first and then building the final cluster
	 * consensus sequence.
	 * 
	 * @param positiveStrand
	 *            does the consensus read map on the positive strand or negative
	 *            strand?
	 * @return
	 * @throws IOException
	 */
	/**
	 * @param positiveStrand
	 * @return
	 * @throws IOException
	 */
	public String collapseMike(BufferedWriter altAlleleWriter,
			boolean positiveStrand) throws IOException
	{
		if (firstTime)
		{
			firstTime = false;
			System.out.println("Scheme: Marianas-Mike");
		}

		// TODO This method will undergo revision as we determine how exactly we
		// want to build the consensus sequence. There are many parameters to
		// play with.
		///////////////////

		// clear the consensii arrays and StringBuilder
		for (int i = 0; i < psConsensus.length; i++)
		{
			psConsensus[i] = -1;
			psConsensusQuals[i] = 0;
			nsConsensusQuals[i] = 0;
		}

		System.arraycopy(psConsensus, 0, nsConsensus, 0, psConsensus.length);
		System.arraycopy(psConsensus, 0, consensus, 0, psConsensus.length);
		consensusSequenceBuilder.setLength(0);
		consensusQualityBuilder.setLength(0);

		// compute per strand consensus sequence. Right now, just choosing the
		// base with highest count at each position.
		for (int i = 0; i < psPositions.length; i++)
		{
			psConsensus[i] = psPositions[i].getMaxCountBase();
			nsConsensus[i] = nsPositions[i].getMaxCountBase();
		}

		// build family consensus sequence.

		for (int i = 0; i < psPositions.length; i++)
		{
			// find this person's genotype. If not available, use ref base
			Byte[] genotype = null;
			Map<Integer, Byte[]> chrMap = genotypes.get(contig);
			if (chrMap != null)
			{
				genotype = chrMap.get(startPosition + i);
			}

			if (genotype == null || genotype.length == 0)
			{
				genotype = new Byte[] { psPositions[i].getRefBase() };
			}

			finalizeStrandConsensusMike(psConsensus, psPositions, i, genotype);
			finalizeStrandConsensusMike(nsConsensus, nsPositions, i, genotype);

			// if same consensii, no problem (including N i.e. -1 i.e. no
			// coverage)
			if (psConsensus[i] == nsConsensus[i])
			{
				consensus[i] = psConsensus[i];
			}
			else if (psConsensus[i] == -1)
			{
				if (isDuplex())
				{
					consensus[i] = -1;
				}
				else if (isNSSimplex() && !isPositionNSSimplex(i))
				{
					consensus[i] = -1;
				}
				else
				{
					// accept
					consensus[i] = nsConsensus[i];
				}
			}
			else if (nsConsensus[i] == -1)
			{
				if (isDuplex())
				{
					consensus[i] = -1;
				}
				else if (isPSSimplex() && !isPositionPSSimplex(i))
				{
					consensus[i] = -1;
				}
				else
				{
					// accept
					consensus[i] = psConsensus[i];
				}
			}
			else
			{
				// positive and negative strands both have non-N consensii but
				// they don't match
				consensus[i] = -1;
			}

			// compute base quality
			byte b = consensus[i];
			if (b == -1)
			{
				psConsensusQuals[i] = nsConsensusQuals[i] = baseQualityRange[0];
			}
			else
			{
				psConsensusQuals[i] = psPositions[i].getConsensusQuality(b);
				nsConsensusQuals[i] = nsPositions[i].getConsensusQuality(b);
			}

			// you have the consensus and quality here. If it is non-genotype,
			// write the alt allele info
			if (isAlt(consensus[i], genotype))
			{
				writeAltAlleleInfo(i, genotype, altAlleleWriter);
			}
		}

		// build the consensus sequence and quality
		for (int i = 0; i < consensus.length; i++)
		{
			byte b = consensus[i];
			int quality;
			if (b == -1)
			{
				b = 'N';
				quality = baseQualityRange[0];
			}
			else
			{
				quality = psConsensusQuals[i] + nsConsensusQuals[i];

				// cap at the range boundaries
				if (quality < baseQualityRange[0])
				{
					quality = baseQualityRange[0];
				}
				else if (quality > baseQualityRange[1])
				{
					quality = baseQualityRange[1];
				}
			}

			consensusSequenceBuilder.append((char) b);
			// write printable ascii base quality value
			consensusQualityBuilder.append((char) (quality + 33));
		}

		// Include insertions
		if (isDuplex())
		{
			for (GenotypeID genotypeID : psSpecialGenotypes.keySet())
			{
				if (genotypeID.type != GenotypeEventType.INSERTION)
				{
					continue;
				}

				Genotype negative = nsSpecialGenotypes.get(genotypeID);

				// must be present on both strands, must have at least
				// minConsensusPercent%
				// support on each strand
				// using 50% instead of minConsensusPercent% for the time being
				if (negative == null
						|| negative.totalSupportingCoverage < (nsReadCount * 50)
								/ 100
						|| psSpecialGenotypes.get(
								genotypeID).totalSupportingCoverage < (psReadCount
										* 50) / 100)
				{
					continue;
				}

				// insert the insertion
				int index = genotypeID.position - startPosition + 1;
				char[] bases = new char[genotypeID.alt.length - 1];
				char[] quals = getInsertionConsensusQuals(genotypeID);
				// get the insertion bases, without the ref base
				for (int i = 0; i < bases.length; i++)
				{
					bases[i] = (char) genotypeID.alt[i + 1];
				}

				consensusSequenceBuilder.insert(index, bases);
				consensusQualityBuilder.insert(index, quals);
			}
		}
		else
		{
			// the family is simplex

			Map<GenotypeID, Genotype> specialGenotypes = null;
			int readCount = -1;

			if (psReadCount > 0)
			{
				specialGenotypes = psSpecialGenotypes;
				readCount = psReadCount;
			}
			else
			{
				specialGenotypes = nsSpecialGenotypes;
				readCount = nsReadCount;
			}

			for (GenotypeID genotypeID : specialGenotypes.keySet())
			{
				if (genotypeID.type != GenotypeEventType.INSERTION)
				{
					continue;
				}

				// must have at least minConsensusPercent% support on the strand
				// using 50% instead of minConsensusPercent% for the time being
				if (specialGenotypes.get(
						genotypeID).totalSupportingCoverage < (readCount * 50)
								/ 100)
				{
					continue;
				}

				// insert the insertion
				int index = genotypeID.position - startPosition + 1;
				char[] bases = new char[genotypeID.alt.length - 1];
				char[] quals = getInsertionConsensusQuals(genotypeID);
				// get the insertion bases, without the ref base
				for (int i = 0; i < bases.length; i++)
				{
					bases[i] = (char) genotypeID.alt[i + 1];
				}

				consensusSequenceBuilder.insert(index, bases);
				consensusQualityBuilder.insert(index, quals);
			}
		}

		// trim trailing N's
		int index = consensusSequenceBuilder.length() - 1;
		while (index - basesToTrim > 0)
		{
			if (consensusSequenceBuilder.charAt(index) != 'N'
					&& consensusSequenceBuilder
							.charAt(index - basesToTrim) != 'N')
			{
				break;
			}

			index--;
		}

		// the consensus is all N's
		if (index - basesToTrim == 0)
		{
			return null;
		}

		consensusSequenceBuilder.setLength(index + 1);
		consensusQualityBuilder.setLength(index + 1);

		// trim the leading and trailing n bases to avoid non-genomic bases
		if (consensusSequenceBuilder.length() < 2 * basesToTrim)
		{
			return null;
		}

		consensusSequenceBuilder.delete(0, basesToTrim);
		consensusQualityBuilder.delete(0, basesToTrim);
		int l = consensusSequenceBuilder.length();
		consensusSequenceBuilder.delete(l - basesToTrim, l);
		consensusQualityBuilder.delete(l - basesToTrim, l);

		// remove deletion bases
		l = consensusSequenceBuilder.length();
		StringBuilder seq = new StringBuilder(consensusQualityBuilder.length());
		StringBuilder qual = new StringBuilder(
				consensusSequenceBuilder.length());
		for (int i = 0; i < l; i++)
		{
			char c = consensusSequenceBuilder.charAt(i);
			if (c == 'D')
			{
				continue;
			}

			seq.append(c);
			qual.append(consensusQualityBuilder.charAt(i));
		}

		char[] sequence = seq.toString().toCharArray();
		char[] qualities = qual.toString().toCharArray();

		// if all bases were D.
		// TODO see if there is a better way to write this method.
		if (sequence.length == 0)
		{
			return null;
		}

		// reverse the strand if necessary
		if (!positiveStrand)
		{
			Util.reverseComplement(sequence);
			Util.reverse(qualities);
		}

		StringBuilder collapsedSequenceInfo = new StringBuilder();

		collapsedSequenceInfo.append(contig).append("\t").append(startPosition)
				.append("\t").append(UMI).append("\t").append(psReadCount)
				.append("\t").append(nsReadCount).append("\t")
				.append(getMatePosition()).append("\t").append(sequence)
				.append("\t").append(qualities);

		return collapsedSequenceInfo.toString();
	}

	/**
	 * build consensus sequence from the pileup. This involves building
	 * consensus sequences per strand first and then building the final cluster
	 * consensus sequence.
	 * 
	 * @param positiveStrand
	 *            does the consensus read map on the positive strand or negative
	 *            strand?
	 * @return
	 * @throws IOException
	 */
	/**
	 * @param positiveStrand
	 * @return
	 * @throws IOException
	 */
	public String collapseEP(BufferedWriter altAlleleWriter,
			boolean positiveStrand) throws IOException
	{
		if (firstTime)
		{
			firstTime = false;
			System.out.println("Scheme: Marianas-EP");
		}

		// TODO This method will undergo revision as we determine how exactly we
		// want to build the consensus sequence. There are many parameters to
		// play with.
		///////////////////

		// clear the consensii arrays and StringBuilder
		for (int i = 0; i < psConsensus.length; i++)
		{
			psConsensus[i] = -1;
			psConsensusQuals[i] = 0;
			nsConsensusQuals[i] = 0;
		}

		System.arraycopy(psConsensus, 0, nsConsensus, 0, psConsensus.length);
		System.arraycopy(psConsensus, 0, consensus, 0, psConsensus.length);
		consensusSequenceBuilder.setLength(0);
		consensusQualityBuilder.setLength(0);

		// compute per strand consensus sequence
		for (int i = 0; i < psPositions.length; i++)
		{
			psConsensus[i] = psPositions[i].getConsensus();
			nsConsensus[i] = nsPositions[i].getConsensus();
		}

		// build family consensus sequence.

		for (int i = 0; i < psPositions.length; i++)
		{
			// find this person's genotype. If not available, use ref base
			Byte[] genotype = null;
			Map<Integer, Byte[]> chrMap = genotypes.get(contig);
			if (chrMap != null)
			{
				genotype = chrMap.get(startPosition + i);
			}

			if (genotype == null || genotype.length == 0)
			{
				genotype = new Byte[] { psPositions[i].getRefBase() };
			}

			finalizeStrandConsensusEP(psConsensus, psPositions, i, genotype);
			finalizeStrandConsensusEP(nsConsensus, nsPositions, i, genotype);

			// if same consensii, no problem (including N i.e. -1 i.e. no
			// coverage)
			if (psConsensus[i] == nsConsensus[i])
			{
				consensus[i] = psConsensus[i];
			}
			else if (psConsensus[i] == -1)
			{
				if (isDuplex() && isAlt(nsConsensus[i], genotype))
				{
					// skip
					// duplex alt must be supported on both strands
				}
				else if (isNSSimplex() && !isPositionNSSimplex(i))
				{
					// skip
					// simplex family must also be simplex at the current
					// position to accept consensus
				}
				else
				{
					// accept
					consensus[i] = nsConsensus[i];
				}
			}
			else if (nsConsensus[i] == -1)
			{
				if (isDuplex() && isAlt(psConsensus[i], genotype))
				{
					// skip
					// duplex alt must be supported on both strands
				}
				else if (isPSSimplex() && !isPositionPSSimplex(i))
				{
					// skip
					// simplex family must also be simplex at the current
					// position to accept consensus
				}
				else
				{
					// accept
					consensus[i] = psConsensus[i];
				}
			}
			else
			{
				// in case the two strands have different consensus bases, fall
				// back on genotype, if one of them is genotype
				int j;
				for (j = 0; j < genotype.length; j++)
				{
					byte g = genotype[j];
					if (psConsensus[i] == g || nsConsensus[i] == g)
					{
						consensus[i] = g;
						break;
					}
				}

				// could not assign genotype so far, make it N
				if (j == genotype.length)
				{
					consensus[i] = -1;
				}
			}

			// compute base quality
			byte b = consensus[i];
			if (b == -1)
			{
				psConsensusQuals[i] = nsConsensusQuals[i] = baseQualityRange[0];
			}
			else
			{
				psConsensusQuals[i] = psPositions[i].getConsensusQuality(b);
				nsConsensusQuals[i] = nsPositions[i].getConsensusQuality(b);
			}

			// you have the consensus and quality here. If it is non-genotype,
			// write the alt allele info
			if (isAlt(consensus[i], genotype))
			{
				writeAltAlleleInfo(i, genotype, altAlleleWriter);
			}
		}

		// build the consensus sequence and quality
		for (int i = 0; i < consensus.length; i++)
		{
			byte b = consensus[i];
			int quality;
			if (b == -1)
			{
				b = 'N';
				quality = baseQualityRange[0];
			}
			else
			{
				quality = psConsensusQuals[i] + nsConsensusQuals[i];

				// cap at the range boundaries
				if (quality < baseQualityRange[0])
				{
					quality = baseQualityRange[0];
				}
				else if (quality > baseQualityRange[1])
				{
					quality = baseQualityRange[1];
				}
			}

			consensusSequenceBuilder.append((char) b);
			// write printable ascii base quality value
			consensusQualityBuilder.append((char) (quality + 33));
		}

		// Include insertions
		if (isDuplex())
		{
			for (GenotypeID genotypeID : psSpecialGenotypes.keySet())
			{
				if (genotypeID.type != GenotypeEventType.INSERTION)
				{
					continue;
				}

				Genotype negative = nsSpecialGenotypes.get(genotypeID);

				// must be present on both strands, must have at least
				// minConsensusPercent%
				// support on each strand
				// using 50% instead of minConsensusPercent% for the time being
				if (negative == null
						|| negative.totalSupportingCoverage < (nsReadCount * 50)
								/ 100
						|| psSpecialGenotypes.get(
								genotypeID).totalSupportingCoverage < (psReadCount
										* 50) / 100)
				{
					continue;
				}

				// insert the insertion
				int index = genotypeID.position - startPosition + 1;
				char[] bases = new char[genotypeID.alt.length - 1];
				char[] quals = getInsertionConsensusQuals(genotypeID);
				// get the insertion bases, without the ref base
				for (int i = 0; i < bases.length; i++)
				{
					bases[i] = (char) genotypeID.alt[i + 1];
				}

				consensusSequenceBuilder.insert(index, bases);
				consensusQualityBuilder.insert(index, quals);
			}
		}
		else
		{
			// the family is simplex

			Map<GenotypeID, Genotype> specialGenotypes = null;
			int readCount = -1;

			if (psReadCount > 0)
			{
				specialGenotypes = psSpecialGenotypes;
				readCount = psReadCount;
			}
			else
			{
				specialGenotypes = nsSpecialGenotypes;
				readCount = nsReadCount;
			}

			for (GenotypeID genotypeID : specialGenotypes.keySet())
			{
				if (genotypeID.type != GenotypeEventType.INSERTION)
				{
					continue;
				}

				// must have at least minConsensusPercent% support on the strand
				// using 50% instead of minConsensusPercent% for the time being
				if (specialGenotypes.get(
						genotypeID).totalSupportingCoverage < (readCount * 50)
								/ 100)
				{
					continue;
				}

				// insert the insertion
				int index = genotypeID.position - startPosition + 1;
				char[] bases = new char[genotypeID.alt.length - 1];
				char[] quals = getInsertionConsensusQuals(genotypeID);
				// get the insertion bases, without the ref base
				for (int i = 0; i < bases.length; i++)
				{
					bases[i] = (char) genotypeID.alt[i + 1];
				}

				consensusSequenceBuilder.insert(index, bases);
				consensusQualityBuilder.insert(index, quals);
			}
		}

		// trim trailing N's
		int index = consensusSequenceBuilder.length() - 1;
		while (index - basesToTrim > 0)
		{
			if (consensusSequenceBuilder.charAt(index) != 'N'
					&& consensusSequenceBuilder
							.charAt(index - basesToTrim) != 'N')
			{
				break;
			}

			index--;
		}

		// the consensus is all N's
		if (index - basesToTrim == 0)
		{
			return null;
		}

		consensusSequenceBuilder.setLength(index + 1);
		consensusQualityBuilder.setLength(index + 1);

		// trim the leading and trailing n bases to avoid non-genomic bases
		if (consensusSequenceBuilder.length() < 2 * basesToTrim)
		{
			return null;
		}

		consensusSequenceBuilder.delete(0, basesToTrim);
		consensusQualityBuilder.delete(0, basesToTrim);
		int l = consensusSequenceBuilder.length();
		consensusSequenceBuilder.delete(l - basesToTrim, l);
		consensusQualityBuilder.delete(l - basesToTrim, l);

		// remove deletion bases
		l = consensusSequenceBuilder.length();
		StringBuilder seq = new StringBuilder(consensusQualityBuilder.length());
		StringBuilder qual = new StringBuilder(
				consensusSequenceBuilder.length());
		for (int i = 0; i < l; i++)
		{
			char c = consensusSequenceBuilder.charAt(i);
			if (c == 'D')
			{
				continue;
			}

			seq.append(c);
			qual.append(consensusQualityBuilder.charAt(i));
		}

		char[] sequence = seq.toString().toCharArray();
		char[] qualities = qual.toString().toCharArray();

		// if all bases were D.
		// TODO see if there is a better way to write this method.
		if (sequence.length == 0)
		{
			return null;
		}

		// reverse the strand if necessary
		if (!positiveStrand)
		{
			Util.reverseComplement(sequence);
			Util.reverse(qualities);
		}

		StringBuilder collapsedSequenceInfo = new StringBuilder();

		collapsedSequenceInfo.append(contig).append("\t").append(startPosition)
				.append("\t").append(UMI).append("\t").append(psReadCount)
				.append("\t").append(nsReadCount).append("\t")
				.append(getMatePosition()).append("\t").append(sequence)
				.append("\t").append(qualities);

		return collapsedSequenceInfo.toString();
	}

	/**
	 * create a permutation of numbers from 0 (inclusive) to max (exclusive)
	 * 
	 * @param length
	 * @return
	 */
	private int[] getIndexPermutation(int max)
	{
		if (max == 1)
		{
			return new int[] { 0 };
		}
		else if (max == 2)
		{
			int number = StaticResources.rng.nextInt(10000);
			number = number % max;
			return new int[] { number, 1 - number };
		}

		int[] permutation = new int[max];
		TIntHashSet set = new TIntHashSet();
		for (int index = 0; index < permutation.length; index++)
		{
			int number = StaticResources.rng.nextInt(10000) % max;
			if (set.contains(number))
			{
				continue;
			}

			set.add(number);
			permutation[index] = number;
		}

		return permutation;

	}

	/**
	 * whether the family is duplex
	 * 
	 * @return
	 */
	private boolean isDuplex()
	{
		if (psReadCount > 0 && nsReadCount > 0)
		{
			return true;
		}

		return false;
	}

	/**
	 * whether the family is simplex with coverage on positive strand
	 * 
	 * @return
	 */
	private boolean isPSSimplex()
	{
		if (psReadCount >= 3)
		{
			return true;
		}

		return false;
	}

	/**
	 * whether the family is simplex with coverage on negative strand
	 * 
	 * @return
	 */
	private boolean isNSSimplex()
	{
		if (nsReadCount >= 3)
		{
			return true;
		}

		return false;
	}

	/**
	 * whether the given position is simplex with coverage on positive strand
	 * 
	 * @return
	 */
	private boolean isPositionPSSimplex(int i)
	{
		if (psPositions[i].getCoverage() >= 3)
		{
			return true;
		}

		return false;
	}

	/**
	 * whether the given position is simplex with coverage on negative strand
	 * 
	 * @return
	 */
	private boolean isPositionNSSimplex(int i)
	{
		if (nsPositions[i].getCoverage() >= 3)
		{
			return true;
		}

		return false;
	}

	/**
	 * 
	 * @param genotypeID
	 * @return ascii printable qualities for the insertion bases
	 */
	private char[] getInsertionConsensusQuals(GenotypeID genotypeID)
	{
		int psCount = -1;
		int nsCount = -1;
		int[] psQuals = null;
		int[] nsQuals = null;
		double psReplicationFactor = -1;
		double nsReplicationFactor = -1;

		if (psReadCount > 0)
		{
			psCount = psSpecialGenotypes
					.get(genotypeID).totalSupportingCoverage;
			psQuals = psInsertionQualities.get(genotypeID);
			psReplicationFactor = FastMath.log(2, psCount) + 1;
		}

		if (nsReadCount > 0)
		{
			nsCount = nsSpecialGenotypes
					.get(genotypeID).totalSupportingCoverage;
			nsQuals = nsInsertionQualities.get(genotypeID);
			nsReplicationFactor = FastMath.log(2, nsCount) + 1;
		}

		char[] quals = new char[psQuals == null ? nsQuals.length
				: psQuals.length];
		for (int i = 0; i < quals.length; i++)
		{
			int p = 0;
			int n = 0;

			if (psReadCount > 0)
			{
				p = (int) (((psQuals[i] * 1.0) / psCount)
						* psReplicationFactor);
			}

			if (nsReadCount > 0)
			{
				n = (int) (((nsQuals[i] * 1.0) / nsCount)
						* nsReplicationFactor);
			}

			int qual = p + n;

			// cap at the range boundaries
			if (qual < baseQualityRange[0])
			{
				qual = baseQualityRange[0];
			}
			else if (qual > baseQualityRange[1])
			{
				qual = baseQualityRange[1];
			}

			// make ascii printable quality values
			quals[i] = (char) (qual + 33);
		}

		return quals;
	}

	/**
	 * for alt, check if non-consensus count is below threshold
	 * 
	 * @param i
	 * @param genotype
	 * @param indexes
	 */
	private void finalizeStrandConsensus(byte[] consensus,
			PositionPileup[] pileup, int index, Byte[] genotype)
	{
		if (!isAlt(consensus[index], genotype))
		{
			return;
		}

		int coverage = pileup[index].getCoverage();
		double consensusPercent = (pileup[index].getCount(consensus[index])
				* 100.0d) / coverage;

		int minPercent = minConsensusPercent;

		// use deletion min consensus percent
		// is this even necessary?? This is taken care of by inclusion of
		// deletion genotype from pileup.
		if (consensus[index] == 'D')
		{
			minPercent = deletionMinConsensusPercent;
		}

		if (consensusPercent <= minPercent)
		{
			// fall back on genotype?
			// prefer genotype with highest count?
			// consensus[index] = genotype[0];
			consensus[index] = -1;
		}

	}

	/**
	 * 
	 * 
	 * @param i
	 * @param genotype
	 * @param indexes
	 */
	private void finalizeStrandConsensusMike(byte[] consensus,
			PositionPileup[] pileup, int index, Byte[] genotype)
	{
		if (consensus[index] == -1)
		{
			return;
		}

		int coverage = pileup[index].getCoverage();
		double consensusPercent = (pileup[index].getCount(consensus[index])
				* 100.0d) / coverage;

		if (consensusPercent + 0.01 < minConsensusPercent)
		{
			// fall back on genotype?
			// prefer genotype with highest count?
			// consensus[index] = genotype[0];
			consensus[index] = -1;
		}

	}

	/**
	 * for alt, check if non-consensus count is below threshold
	 * 
	 * @param i
	 * @param genotype
	 * @param indexes
	 */
	private void finalizeStrandConsensusEP(byte[] strandConsensus,
			PositionPileup[] pileup, int index, Byte[] genotype)
	{
		double threshold = -1;
		if (isAlt(strandConsensus[index], genotype))
		{
			threshold = (minConsensusPercent * 1.0) / 100;
		}
		else
		{
			// genotype call threshold. Make it a parameter?
			threshold = 0.8;
		}

		// TIntArrayList[] bq = pileup[index].getBaseQualities();
		// double[] LR = pileup[index].getLR();
		double maxLR = pileup[index].getLR(strandConsensus[index]);

		if (maxLR < threshold)
		{
			strandConsensus[index] = -1;
		}

	}

	private void writeAltAlleleInfo(int i, Byte[] genotype,
			BufferedWriter altAlleleWriter) throws IOException
	{
		String family = null;
		if (isDuplex())
		{
			family = "Duplex";
		}
		else if (isPSSimplex())
		{
			family = "PSSimplex";
		}
		else if (isNSSimplex())
		{
			family = "NSSimplex";
		}
		else
		{
			family = "Unfiltered";
		}

		// chr, start, UMI, altposition, offset, genotype, consensus, pscount,
		// altpscount, psquality, nscount, altnscount, nsquality
		altAlleleWriter.write(contig + "\t");
		altAlleleWriter.write(startPosition + "\t");
		altAlleleWriter.write(UMI + "\t");
		altAlleleWriter.write((startPosition + i) + "\t");
		altAlleleWriter.write(i + "\t");
		for (int k = 0; k < genotype.length; k++)
		{
			altAlleleWriter.write((char) genotype[k].byteValue());
		}

		altAlleleWriter.write("\t");
		altAlleleWriter.write((char) consensus[i] + "\t");
		altAlleleWriter.write(psPositions[i].getCoverage() + "\t");
		altAlleleWriter.write(psPositions[i].getCount(consensus[i]) + "\t");
		altAlleleWriter.write(psConsensusQuals[i] + "\t");
		altAlleleWriter.write(nsPositions[i].getCoverage() + "\t");
		altAlleleWriter.write(nsPositions[i].getCount(consensus[i]) + "\t");
		altAlleleWriter.write(nsConsensusQuals[i] + "\t");
		altAlleleWriter.write(family + "\n");

	}

	private void writeInsertionInfo(int i, GenotypeID genotypeID,
			int psSupporting, int psTotal, int nsSupporting, int nsTotal,
			BufferedWriter insertionsWriter) throws IOException
	{
		String family = null;
		if (isDuplex())
		{
			family = "Duplex";
		}
		else if (isPSSimplex())
		{
			family = "PSSimplex";
		}
		else if (isNSSimplex())
		{
			family = "NSSimplex";
		}
		else
		{
			family = "Unfiltered";
		}

		// chr, start, UMI, altposition, offset, genotype, consensus, pscount,
		// altpscount, psquality, nscount, altnscount, nsquality
		insertionsWriter.write(contig + "\t");
		insertionsWriter.write(startPosition + "\t");
		insertionsWriter.write(UMI + "\t");
		insertionsWriter.write((startPosition + i - 1) + "\t");
		insertionsWriter.write((i - 1) + "\t");
		insertionsWriter.write((char) genotypeID.ref[0]);
		insertionsWriter.write("\t");
		for (int k = 0; k < genotypeID.alt.length; k++)
		{
			insertionsWriter.write((char) genotypeID.alt[k]);
		}

		insertionsWriter.write("\t");
		insertionsWriter.write(psTotal + "\t");
		insertionsWriter.write(psSupporting + "\t");
		insertionsWriter.write("QU" + "\t");
		insertionsWriter.write(nsTotal + "\t");
		insertionsWriter.write(nsSupporting + "\t");
		insertionsWriter.write("QU" + "\t");
		insertionsWriter.write(family + "\n");

	}

	private boolean isAlt(byte allele, Byte[] genotype)
	{
		if (allele == -1) // || (allele == 'D' && index >= 115))
		{
			return false;
		}

		for (int i = 0; i < genotype.length; i++)
		{
			if (genotype[i].byteValue() == allele)
			{
				return false;
			}
		}

		return true;
	}

	private String getMatePosition()
	{
		int max = 0;
		String position = null;
		for (String key : matePositions.keySet())
		{
			int freq = matePositions.get(key);
			if (freq > max)
			{
				max = freq;
				position = key;
			}
		}

		return position;
	}

	public String getContig()
	{
		return contig;
	}

	public int getStartPosition()
	{
		return startPosition;
	}

	public String getUMI()
	{
		return UMI;
	}

	public int getTotalCount()
	{
		return psReadCount + nsReadCount;
	}
}
