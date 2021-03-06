# Marianas: Deep Next Generation Sequencing, Molecular Barcoding


This software was developed at the Innovation Lab, Center for Molecular Oncology, Memorial Sloan Kettering Cancer Center (MSKCC).

Marianas is a Java software that processes NGS sequencing data where reads contain molecular barcodes (UMIs). This processing involves 3 distinct steps at various points in the workflow:
1. UMI clipping: Identify UMIs and bridge bases. ADD UMI pair to fastq read name, discard bridge bases
2. Collapsing: Process standard bam file containing aligned, UMI-clipped reads. Identify PCR duplicates by using mapping position and UMIs. Correct PCR and sequencing errors by taking consensus among PCR duplicates. Create collapsed fastq files
3. Bam separation: From the collapsed bam, create separate bam files contianing duplex-only and simplex-only reads



## Java

Java 1.8 or above is required.

## Dependencies (bundled with the release jar)

1. BioinfoUtils
2. HTSJDK
3. Google Guava
4. Apache Commons IO


## Using Marianas

**1. UMI clipping**

java -server -Xms8g -Xmx8g -cp Marianas.jar org.mskcc.marianas.umi.duplex.fastqprocessing.ProcessLoopUMIFastq read1.fastq.gz read2.fastq.gz umi-length

This will create a pair of _umi-clipped.fastq.gz files in the current working directory


**2. Collapsing and making consensus reads**


Collapsing involves 3 sub-steps: First pass that collapses the "left" read cluster of the read pairs, followed by a sort step on the first pass output, followed by second pass that collapses the "right" read cluster of the read pairs.


A.
  
java -server -Xms8g -Xmx8g -cp Marianas.jar org.mskcc.marianas.umi.duplex.DuplexUMIBamToCollapsedFastqFirstPass bam-file pileup-file minMappingQuality minBaseQuality UMIMismatches UMIWobble minConsensusPercent reference-fasta-file

The pileup file is generated by running Waltz bam metrics module. The values currently used for some of the parameters are as follows:  
minMappingQuality: 1  
minBaseQuality: 20  
UMIMismatches: 0  
UMIWobble: 1  
minConsensusPercent: 90  

This will produce some QC files as well as first-pass.txt in the current working directory. first-pass.txt contains the collapsed seuquences and base qualities for the first pass. This file must be sorted before running second pass.   

B.

sort -S 8G -k 6,6n -k 8,8n first-pass.txt > first-pass.mate-position-sorted.txt

C.

java -server -Xms8g -Xmx8g -cp Marianas.jar org.mskcc.marianas.umi.duplex.DuplexUMIBamToCollapsedFastqSecondPass standard-bam-file pileup-file minMappingQuality minBaseQuality mismatches wobble minConsensusPercent reference-fasta-file first-pass.mate-position-sorted.txt

This will produce collapsed_R1_.fastq and collapsed_R2_.fastq files along with some QC files.


**3. Separating simplex and duplex bams from original collapsed bam**

java -server -Xms8g -Xmx8g -cp Marianas.jar org.mskcc.marianas.umi.duplex.postprocessing.SeparateBams collapsed.bam

This will produce collapsed-duplex.bam and collapsed-simplex.bam (based on the input bam file name) in the current working directory.


**4. Polishing**

A. Building frequency tables

java -server -Xms8g -Xmx8g -cp Marianas.jar org.mskcc.marianas.polishing.NoiseFrequencyBuilder pileup-directory

where pileup-directory contains Waltz pileups for the chosen set of normals. This will create 2 files: af-frequencies.txt and count-frequencies.txt. This step is executed only when there is a change in the chosen set of normals.


B. Polishing variants

java -server -Xms8g -Xmx8g -cp Marianas.jar org.mskcc.marianas.polishing.Polisher maf-file depth-column-name alt-count-column-name af-frequencies-file count-frequencies-file

These columns are required in the input maf file: Chromosome, Start_Position, Variant_Type, Reference_Allele, Tumor_Seq_Allele2.

The command will create a -polished.maf file that has 3 additional columns at the end:

Polishing_Position_Average_Coverage: Average coverage at the mutation position in the normal set  
Polishing_P_Value: Polishing p-value that can be used to filter out low confidence calls  
Fragment_Count->Samples_Map: A set of key-value pairs for the mutation where key is the alt count and value is the number of normals with that alt count    

Polishing will only be performed for point substitutions and single base deletions. The above fields will contain a "-" for other types of mutations.








