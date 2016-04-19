/////////////////////////////////////////////////////////////////////////////////
//
// This file is part of Cpipe.
// 
// Cpipe is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, under version 3 of the License, subject
// to additional terms compatible with the GNU General Public License version 3,
// specified in the LICENSE file that is part of the Cpipe distribution.
// 
// Cpipe is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with Cpipe.  If not, see <http://www.gnu.org/licenses/>.
// 
/////////////////////////////////////////////////////////////////////////////////

///////////////////////////////////////////////////////////////////
// stages
///////////////////////////////////////////////////////////////////
calc_coverage_stats = {
    doc "Calculate coverage across a target region using Bedtools"
    output.dir="qc"

    var MIN_ONTARGET_PERCENTAGE : 50

    // transform("bam") to([sample + ".cov.txt", sample + ".cov.gz", sample + ".exome.txt", sample + ".ontarget.txt"]) {
    produce("${sample}.cov.txt", "${sample}.cov.gz", "${sample}.exome.txt", "${sample}.ontarget.txt") {
        // only calculate coverage for bases overlapping the capture
        def safe_tmp_dir = [TMPDIR, UUID.randomUUID().toString()].join( File.separator )

        // coverage calculation for qc_report.py
        exec """
          mkdir -p "$safe_tmp_dir"
        
          $BEDTOOLS/bin/bedtools intersect -a $target_bed_file.${sample}.bed -b $EXOME_TARGET > "$safe_tmp_dir/intersect.bed"

          $BEDTOOLS/bin/coverageBed -d -abam $input.bam -b "$safe_tmp_dir/intersect.bed" > $output.txt

          gzip < $output.txt > $output2.gz

          $BEDTOOLS/bin/coverageBed -d -abam $input.bam -b $EXOME_TARGET > $output3.txt
        
          $SAMTOOLS/samtools view -L $COMBINED_TARGET $input.bam | wc | awk '{ print \$1 }' > $output4.txt

          rm -r "$safe_tmp_dir"
        """
     }

}

check_ontarget_perc = {
    var MIN_ONTARGET_PERCENTAGE : 50,
        input_ontarget_file: "qc/${sample}.ontarget.txt" // something about segments messes up $input
    check {
        exec """
            RAW_READ_COUNT=`cat $input_ontarget_file`

            ONTARGET_PERC=`grep -A 1 LIBRARY $input.metrics | tail -1 | awk '{ print int(((\$3 * 2) / "'"$RAW_READ_COUNT"'"))*100 }'`

            [ $RAW_READ_COUNT -eq 0 -o $ONTARGET_PERC -lt $MIN_ONTARGET_PERCENTAGE ]

             """
    } otherwise {
        send text {"On target read percentage for $sample < $MIN_ONTARGET_PERCENTAGE"} to channel: cpipe_operator 
    }
}

calculate_qc_statistics = {
    doc "Calculate additional qc statistics"
    output.dir="qc"
    // transform("bam") to(sample + ".fragments.tsv") {
    produce("${sample}.fragments.tsv") {
        exec """
            $SAMTOOLS/samtools view $input.bam | python $SCRIPTS/calculate_qc_statistics.py > $output.tsv
        """
    }
}

gatk_depth_of_coverage = {

    doc "Calculate statistics about depth of coverage for an alignment using GATK"

    output.dir = "qc"
    transform("bam") to("."+target_name + ".cov.sample_cumulative_coverage_proportions", 
                         "."+target_name + ".cov.sample_interval_statistics") { 
        exec """
            $JAVA -Xmx4g -jar $GATK/GenomeAnalysisTK.jar 
               -R $REF
               -T DepthOfCoverage 
               -o $output.sample_cumulative_coverage_proportions.prefix
               --omitDepthOutputAtEachBase
               -I $input.bam
               -ct 1 -ct 10 -ct 20 -ct 50 -ct 100
               -L $target_bed_file.${sample}.bed
        """
    }
}

insert_size_metrics = {

    doc "Generates statistics about distribution of DNA fragment sizes"

    var MIN_MEDIAN_INSERT_SIZE : 70,
        MAX_MEDIAN_INSERT_SIZE : 240

    output.dir="qc"
    exec """
        $JAVA -Xmx4g -jar $PICARD_HOME/picard.jar CollectInsertSizeMetrics INPUT=$input.bam O=$output.txt H=$output.pdf
    """

    check {
        exec """
              INSERT_SIZE=`grep -A 1 MEDIAN_INSERT_SIZE $output.txt | cut -f 1 | tail -1 | sed 's/\\.[0-9]*//'`

              echo "Median insert size = $INSERT_SIZE"

              [ $INSERT_SIZE -gt $MIN_MEDIAN_INSERT_SIZE ] && [ $INSERT_SIZE -lt $MAX_MEDIAN_INSERT_SIZE ]
             """, "local"
    } otherwise {
        send text {"""
            WARNING: Insert size distribution for $sample has median out of 
            range $MIN_MEDIAN_INSERT_SIZE - $MAX_MEDIAN_INSERT_SIZE
        """} to channel: cpipe_operator, file: output.pdf
    }
}

gap_report = {
    output.dir="results"
    
    var LOW_COVERAGE_THRESHOLD : 15,
        LOW_COVERAGE_WIDTH : 1,
        input_coverage_file: "qc/${sample}.cov.txt" // something about segments messes up $input

    produce("${run_id}_${sample}.gap.csv") {
        exec """
            python $SCRIPTS/gap_annotator.py --min_coverage_ok $LOW_COVERAGE_THRESHOLD --min_gap_width $LOW_COVERAGE_WIDTH --coverage $input_coverage_file --db $BASE/designs/genelists/refgene.txt > $output.csv
        """
    }
}

summary_report = {
    requires sample_metadata_file : "File describing meta data for pipeline run (usually, samples.txt)"

    output.dir="results"

    var input_coverage_file: "qc/${sample}.cov.txt", // something about segments messes up $input
        input_exome_file: "qc/${sample}.exome.txt", 
        input_ontarget_file: "qc/${sample}.ontarget.txt",
        input_fragments_file: "qc/${sample}.fragments.tsv"

    produce("${run_id}_${sample}.summary.htm", "${run_id}_${sample}.summary.md", "${run_id}_${sample}.summary.karyotype.tsv") {
        exec """
            python $SCRIPTS/qc_report.py --report_cov $input_coverage_file --exome_cov $input_exome_file --ontarget $input_ontarget_file ${inputs.metrics.withFlag("--metrics")} --study $sample --meta $sample_metadata_file --threshold 20 --classes GOOD:95:GREEN,PASS:80:ORANGE,FAIL:0:RED --gc $target_gene_file --gene_cov qc/exon_coverage_stats.txt --write_karyotype $output.tsv --fragments $input_fragments_file --padding $INTERVAL_PADDING_CALL,$INTERVAL_PADDING_INDEL,$INTERVAL_PADDING_SNV > $output.md

            python $SCRIPTS/markdown2.py --extras tables < $output.md | python $SCRIPTS/prettify_markdown.py > $output.htm
        """

        branch.karyotype = output.tsv

        send text {"Sequencing Results for Study $sample"} to channel: cpipe_operator, file: output.htm
    }
}

exon_qc_report = {

    requires sample_metadata_file : "File describing meta data for pipeline run (usually, samples.txt)"

    output.dir="results"

    var enable_exon_report : false

    if(!enable_exon_report)  {
        msg "Exon level coverage report not enabled for $target_name"
        return
    }

    produce("${sample}.exon.qc.xlsx", "${sample}.exon.qc.tsv") {
        exec """
             JAVA_OPTS="-Xmx3g" $GROOVY -cp $GROOVY_NGS/groovy-ngs-utils.jar:$EXCEL/excel.jar $SCRIPTS/exon_qc_report.groovy 
                -cov $input.cov.gz
                -targets $target_bed_file
                -refgene $ANNOVAR_DB/hg19_refGene.txt 
                -x $output.xlsx
                -o $output.tsv
        """
    }
}

check_coverage = {

    output.dir = "qc"

    def medianCov
    transform("cov.gz") to("cov.stats.median", "cov.stats.csv") {

        R {"""
            bam.cov = read.table(pipe("gunzip -c $input.cov.gz"), col.names=c("chr","start","end", "gene", "offset", "cov"))
            meds = aggregate(bam.cov$cov, list(bam.cov$gene), median)
            write.csv(data.frame(Gene=meds[,1],MedianCov=meds$x), "$output.csv", quote=F, row.names=F)
            writeLines(as.character(median(bam.cov$cov)), "$output.median")
        """}

        // HACK to ensure file sync on distributed file system
        file(output.dir).listFiles()
        medianCov = Math.round(file(output.median).text.toFloat())
    }

    check {
        exec "[ $medianCov -ge $MEDIAN_COVERAGE_THRESHOLD ]"
    } otherwise {
        // It may seem odd to call this a success, but what we mean by it is that
        // Bpipe should not fail the whole pipeline, merely this branch of it
        succeed report('templates/sample_failure.html') to channel: cpipe_operator, 
                                                           median: medianCov, 
                                                           file:output.csv, 
                                                           subject:"Sample $sample has failed with insufficient median coverage ($medianCov)"
    }
}

check_karyotype = {

    doc "Compare the inferred sex of the sample to the inferred karyotype from the sequencing data"
    exec """
       echo "check_karyotype: enter"
    """

    def karyotype_file = "results/" + run_id + '_' + sample + '.summary.karyotype.tsv'
    check {
        exec """
            [ `grep '^Sex' $karyotype_file | cut -f 2` == "UNKNOWN" ] || [ `grep '^Sex' $karyotype_file | cut -f 2` == `grep 'Inferred Sex' $karyotype_file | cut -f 2` ]
        """
    } otherwise {
        // It may seem odd to call this a success, but what we mean by it is that
        // Bpipe should not fail the whole pipeline, merely this branch of it
        succeed report('templates/sample_failure.html') to channel: cpipe_operator, 
                                                           median: medianCov, 
                                                           file: karyotype_file,
                                                           subject:"Sample $sample has a different sex than inferred from sequencing data"
     }
    exec """
       echo "check_karyotype: exit"
    """
}

qc_excel_report = {

    doc "Create an excel file containing a summary of QC data for all the samples for a given target region"
    exec """
       echo "qc_excel_report: enter"
    """

    var LOW_COVERAGE_THRESHOLD : 15,
        LOW_COVERAGE_WIDTH : 1

    output.dir="results"

    def samples = sample_info.grep { it.value.target == target_name }.collect { it.value.sample }
    produce(target_name + ".qc.xlsx") {
            exec """
                JAVA_OPTS="-Xmx16g -Djava.awt.headless=true" $GROOVY -cp $GROOVY_NGS/groovy-ngs-utils.jar:$EXCEL/excel.jar $SCRIPTS/qc_excel_report.groovy 
                    -s ${target_samples.join(",")} 
                    -t $LOW_COVERAGE_THRESHOLD
                    -w $LOW_COVERAGE_WIDTH
                    -low qc ${inputs.dedup.metrics.withFlag('-metrics')}
                    -o $output.xlsx
                    -p $run_id
                    $inputs.sample_cumulative_coverage_proportions  
                    $inputs.sample_interval_statistics 
                    $inputs.gz
            ""","qc_excel_report"
    }
    exec """
        echo "qc_excel_report: exit"
    """
}

provenance_report = {
    branch.sample = branch.name
    output.dir = "results"
    produce(run_id + '_' + sample + ".provenance.pdf") {
       send report("scripts/provenance_report.groovy") to file: output.pdf
    }
}

filtered_on_exons = {
    doc "Create a bam filtered on exons with 100bp padding and excluding the incidentalome"
    // bedtools exons.bed + padding100bp - incidentalome
    // TODO this might be faster if we sorted the bam and used -sorted
    var GENE_BAM_PADDING: 100

    def safe_tmp = ['tmp', UUID.randomUUID().toString()].join( '' )

    output.dir = "results"

    produce("${run_id}_" + branch.name + ".filtered_on_exons.bam") {
        exec """
            python $SCRIPTS/filter_bed.py --include $BASE/designs/genelists/incidentalome.genes.txt < $BASE/designs/genelists/exons.bed |
            $BEDTOOLS/bin/bedtools slop -g $HG19_CHROM_INFO -b $GENE_BAM_PADDING -i - > $safe_tmp 
            
            python $SCRIPTS/filter_bed.py --exclude $BASE/designs/genelists/incidentalome.genes.txt < $BASE/designs/genelists/exons.bed |
            $BEDTOOLS/bin/bedtools slop -g $HG19_CHROM_INFO -b $GENE_BAM_PADDING -i - | 
            $BEDTOOLS/bin/bedtools subtract -a - -b $safe_tmp | 
            sort -k1,1 -k2,2n |
            $BEDTOOLS/bin/bedtools intersect -a $input.recal.bam -b stdin > $output.bam
    
            rm "$safe_tmp"
        """
    }
}

variant_filtering_report = {
    doc "generate a report of all variants and where they were filtered"
    output.dir = "variants"
    produce("variant_filtering_report.tsv") {
        exec """
            python $SCRIPTS/variant_filtering.py  --source_dir $output.dir > $output
        """
    }
}

variant_bams = {

    doc "Create a bam file for each variant containing only reads overlapping 100bp either side of that variant"

    output.dir = "results/variant_bams"

    from(run_id + "_" + branch.name + '*.lovd.tsv', branch.name + '.*.recal.bam') {   
        // Slight hack here. Produce a log file that bpipe can track to confirm that the bams were produced.
        // Bpipe is not actually tracking the variant bams themselves. 
        produce(branch.name + ".variant_bams_log.txt") {
            exec """
                python $SCRIPTS/variant_bams.py --bam $input.bam --tsv $input.tsv --outdir $output.dir --log $output.txt --samtoolsdir $SAMTOOLS
            """
        }
    }
}

///////////////////////////////////////////////////////////////////
// segments
///////////////////////////////////////////////////////////////////
analysis_ready_reports = segment {
//    parallel doesn't work properly here
//    [ calc_coverage_stats + check_ontarget_perc, calculate_qc_statistics ] + 
//    [ summary_report, exon_qc_report, gap_report ]
    calc_coverage_stats + 
    check_ontarget_perc + 
    calculate_qc_statistics + 
    summary_report + 
    exon_qc_report + 
    gap_report +
    gatk_depth_of_coverage +
    insert_size_metrics +
    filtered_on_exons + index_bam 
}

analysis_ready_checks = segment {
    check_coverage +
    check_karyotype
}

post_analysis = segment {
    variant_bams
}
