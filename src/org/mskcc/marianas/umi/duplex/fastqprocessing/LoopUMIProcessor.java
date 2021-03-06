/**
 * 
 */
package org.mskcc.marianas.umi.duplex.fastqprocessing;

import java.util.HashMap;
import java.util.Map;

/**
 * @author Juber Patel
 *
 */
public class LoopUMIProcessor
{

	public static Map<String, Integer> UMIFrequencies = new HashMap<String, Integer>();
	public static Map<String, Integer> compositeUMIFrequencies = new HashMap<String, Integer>();

	private UMIReadBean read1;
	private UMIReadBean read2;

	public LoopUMIProcessor(String seq1, String qual1, String seq2,
			String qual2, int UMILength)
	{
		read1 = process(seq1, qual1, UMILength);
		read2 = process(seq2, qual2, UMILength);

		if (read1HasUMI() && read2HasUMI())
		{
			// update composite UMI frequencies
			String compositeUMI = compositeUMI();
			Integer freq = compositeUMIFrequencies.get(compositeUMI);
			if (freq == null)
			{
				compositeUMIFrequencies.put(compositeUMI, 1);
			}
			else
			{
				compositeUMIFrequencies.put(compositeUMI, freq + 1);
			}
		}
	}

	/**
	 * determine if the read has valid UMI and separate the UMI and constant
	 * region from the read.
	 * 
	 * 
	 * @param seq
	 * @param qual
	 * @param UMILength
	 * @return
	 */
	private UMIReadBean process(String seq, String qual, int UMILength)
	{
		// check if you can find find support base/s
		int supportBases = countSupportBases(seq, UMILength);

		// can't find support bases
		if (supportBases == -1)
		{
			return null;
		}

		// else make a proper bean
		UMIReadBean read = new UMIReadBean(
				seq.substring(UMILength + supportBases),
				qual.substring(UMILength + supportBases),
				seq.substring(0, UMILength), qual.substring(0, UMILength));

		// update overall UMI frequencies
		Integer freq = UMIFrequencies.get(read.UMI);
		if (freq == null)
		{
			UMIFrequencies.put(read.UMI, 1);
		}
		else
		{
			UMIFrequencies.put(read.UMI, freq + 1);
		}

		return read;
	}

	/**
	 * Apply the IDT Loop UMI rules and see if you can find the appropriate
	 * number of support bases
	 * 
	 * @param seq
	 * @param uMILength
	 * @return number of support bases. -1 if not found
	 */
	private int countSupportBases(String seq, int UMILength)
	{
		char lastUMIChar = seq.charAt(UMILength - 1);

		if (lastUMIChar == 'G' || lastUMIChar == 'C')
		{
			if (seq.charAt(UMILength) == 'T')
			{
				return 1;
			}
		}
		else if (lastUMIChar == 'A' || lastUMIChar == 'T')
		{
			if (seq.charAt(UMILength + 1) == 'T')
			{
				return 2;
			}
		}

		return -1;
	}

	public String compositeUMI()
	{
		return read1.UMI + "+" + read2.UMI;
	}

	public String compositeUMIQuals()
	{
		return read1.UMIQual + "+" + read2.UMIQual;
	}

	public boolean read1HasUMI()
	{
		return read1 != null;
	}

	public boolean read2HasUMI()
	{
		return read2 != null;
	}

	public String seq1()
	{
		return read1.seq;
	}

	public String qual1()
	{
		return read1.qual;
	}

	public String seq2()
	{
		return read2.seq;
	}

	public String qual2()
	{
		return read2.qual;
	}

}
