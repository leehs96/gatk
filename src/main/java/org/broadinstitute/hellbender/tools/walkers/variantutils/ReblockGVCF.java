package org.broadinstitute.hellbender.tools.walkers.variantutils;

import com.google.common.annotations.VisibleForTesting;
import htsjdk.variant.variantcontext.*;
import htsjdk.variant.variantcontext.writer.VariantContextWriter;
import htsjdk.variant.vcf.*;
import org.apache.hadoop.fs.FileContext;
import org.broadinstitute.barclay.argparser.*;
import org.broadinstitute.barclay.help.DocumentedFeature;
import org.broadinstitute.hellbender.cmdline.*;
import org.broadinstitute.hellbender.cmdline.argumentcollections.DbsnpArgumentCollection;
import org.broadinstitute.hellbender.engine.FeatureContext;
import org.broadinstitute.hellbender.engine.ReadsContext;
import org.broadinstitute.hellbender.engine.ReferenceContext;
import org.broadinstitute.hellbender.engine.GATKPath;
import org.broadinstitute.hellbender.engine.VariantWalker;
import org.broadinstitute.hellbender.exceptions.GATKException;
import org.broadinstitute.hellbender.exceptions.UserException;
import org.broadinstitute.hellbender.tools.walkers.annotator.*;
import org.broadinstitute.hellbender.tools.walkers.annotator.allelespecific.AS_QualByDepth;
import org.broadinstitute.hellbender.tools.walkers.annotator.allelespecific.AS_StandardAnnotation;
import org.broadinstitute.hellbender.tools.walkers.annotator.allelespecific.ReducibleAnnotation;
import org.broadinstitute.hellbender.tools.walkers.genotyper.*;
import org.broadinstitute.hellbender.tools.walkers.haplotypecaller.HaplotypeCallerArgumentCollection;
import org.broadinstitute.hellbender.tools.walkers.haplotypecaller.HaplotypeCallerGenotypingEngine;
import org.broadinstitute.hellbender.tools.walkers.haplotypecaller.ReferenceConfidenceMode;
import org.broadinstitute.hellbender.utils.MathUtils;
import org.broadinstitute.hellbender.utils.SimpleInterval;
import org.broadinstitute.hellbender.utils.Utils;
import org.broadinstitute.hellbender.utils.fasta.CachingIndexedFastaSequenceFile;
import org.broadinstitute.hellbender.utils.genotyper.IndexedSampleList;
import org.broadinstitute.hellbender.utils.genotyper.SampleList;
import org.broadinstitute.hellbender.utils.logging.OneShotLogger;
import org.broadinstitute.hellbender.utils.reference.ReferenceUtils;
import org.broadinstitute.hellbender.utils.variant.*;
import org.broadinstitute.hellbender.utils.variant.writers.GVCFWriter;
import picard.cmdline.programgroups.OtherProgramGroup;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Condense homRef blocks in a single-sample GVCF
 *
 * <p>
 * ReblockGVCF compresses a GVCF by merging hom-ref blocks that were produced using the '-ERC GVCF' or '-ERC BP_RESOLUTION' mode of the
 * HaplotypeCaller according to new GQ band parameters.  A joint callset produced with GVCFs reprocessed by ReblockGVCF will have
 * lower precision for hom-ref genotype qualities at variant sites, but the input data footprint can be greatly reduced
 * if the default GQ band parameters are used.</p>
 *
 * <h3>Input</h3>
 * <p>
 * A HaplotypeCaller-produced GVCF to reblock
 * </p>
 *
 * <h3>Output</h3>
 * <p>
 * A smaller GVCF.
 * </p>
 *
 * <h3>Usage example</h3>
 * <pre>
 * gatk ReblockGVCF \
 *   -R reference.fasta \
 *   -V sample1.g.vcf \
 *   -O sample1.reblocked.g.vcf
 * </pre>
 *
 * Invocation as for use with GnarlyGenotyper in the "Biggest Practices"
 * <pre>
 *  gatk ReblockGVCF \
 *    -R reference.fasta \
 *    -V sample1.g.vcf \
 *    -drop-low-quals \
 *    -rgq-threshold 10 \
 *    -do-qual-approx \
 *    -O sample1.reblocked.g.vcf
 *  * </pre>
 *
 * <h3>Caveats</h3>
 * <p>Only single-sample GVCF files produced by HaplotypeCaller can be used as input for this tool.</p>
 * <p>Note that when uncalled alleles are dropped, the original GQ may increase.  Use --keep-all-alts if GQ accuracy is a concern.</p>
 * <h3>Special note on ploidy</h3>
 * <p>This tool assumes diploid genotypes.</p>
 *
 */
@BetaFeature
@CommandLineProgramProperties(summary = "Compress a single-sample GVCF from HaplotypeCaller by merging homRef blocks using new GQ band parameters",
        oneLineSummary = "Condenses homRef blocks in a single-sample GVCF",
        programGroup = OtherProgramGroup.class,
        omitFromCommandLine = true)
@DocumentedFeature
public final class ReblockGVCF extends VariantWalker {

    private static final OneShotLogger logger = new OneShotLogger(ReblockGVCF.class);

    private final static int PLOIDY_TWO = 2;  //assume diploid genotypes

    private boolean isInDeletion = false;  //state variable to keep track of whether this locus is covered by a deletion (even a low quality one we'll change), so we don't generate overlapping ref blocks
    private int bufferEnd = 0;
    private int vcfOutputEnd = 0;

    public static final String DROP_LOW_QUALS_ARG_NAME = "drop-low-quals";
    public static final String RGQ_THRESHOLD_LONG_NAME = "rgq-threshold-to-no-call";
    public static final String RGQ_THRESHOLD_SHORT_NAME = "rgq-threshold";
    public static final String KEEP_ALL_ALTS_ARG_NAME = "keep-all-alts";

    private final class VariantContextBuilderComparator implements Comparator<VariantContextBuilder> {
        @Override
        public int compare(final VariantContextBuilder builder1, final VariantContextBuilder builder2) {
            return (int)(builder1.getStart() - builder2.getStart());
        }
    }

    @Argument(fullName = StandardArgumentDefinitions.OUTPUT_LONG_NAME, shortName = StandardArgumentDefinitions.OUTPUT_SHORT_NAME,
            doc="File to which variants should be written")
    private GATKPath outputFile;

    @ArgumentCollection
    public GenotypeCalculationArgumentCollection genotypeArgs = new GenotypeCalculationArgumentCollection();

    /**
     * Output the band lower bound for each GQ block instead of the min GQ -- for better compression
     */
    @Advanced
    @Argument(fullName=HaplotypeCallerArgumentCollection.OUTPUT_BLOCK_LOWER_BOUNDS, doc = "Output the band lower bound for each GQ block regardless of the data it represents", optional = true)
    private boolean floorBlocks = false;

    @Advanced
    @Argument(fullName=HaplotypeCallerArgumentCollection.GQ_BAND_LONG_NAME, shortName=HaplotypeCallerArgumentCollection.GQ_BAND_SHORT_NAME,
            doc="Exclusive upper bounds for reference confidence GQ bands (must be in [1, 100] and specified in increasing order)")
    public List<Integer> GVCFGQBands = new ArrayList<>();
    {
        GVCFGQBands.add(20); GVCFGQBands.add(100);
    }

    @Advanced
    @Argument(fullName=DROP_LOW_QUALS_ARG_NAME, shortName=DROP_LOW_QUALS_ARG_NAME, doc="Exclude variants and homRef blocks that are GQ0 from the reblocked GVCF to save space; drop low quality/uncalled alleles")
    protected boolean dropLowQuals = false;

    @Advanced
    @Argument(fullName=RGQ_THRESHOLD_LONG_NAME, shortName=RGQ_THRESHOLD_SHORT_NAME, doc="Reference genotype quality (PL[0]) value below which variant sites will be converted to GQ0 homRef calls")
    protected double rgqThreshold = 0.0;

    @Advanced
    @Argument(fullName="do-qual-score-approximation", shortName="do-qual-approx", doc="Add necessary INFO field annotation to perform QUAL approximation downstream; required for GnarlyGenotyper")
    protected boolean doQualApprox = false;

    @Advanced
    @Argument(fullName="allow-missing-hom-ref-data", doc="Fill in homozygous reference genotypes with no PLs and no GQ with PL=[0,0,0].  Necessary for input from Regeneron's WeCall variant caller.")
    protected boolean allowMissingHomRefData = false;

    @Advanced
    @Argument(fullName=KEEP_ALL_ALTS_ARG_NAME, doc="Keep all ALT alleles and full PL array for most accurate GQs")
    protected boolean keepAllAlts = false;

    @Advanced
    @Argument(fullName="genotype-posteriors-key", doc="INFO field key corresponding to the posterior genotype probabilities", optional = true)
    protected String posteriorsKey = null;

    /**
     * The rsIDs from this file are used to populate the ID column of the output.  Also, the DB INFO flag will be set when appropriate. Note that dbSNP is not used in any way for the calculations themselves.
     */
    @ArgumentCollection
    protected DbsnpArgumentCollection dbsnp = new DbsnpArgumentCollection();

    // the genotyping engine
    private HaplotypeCallerGenotypingEngine genotypingEngine;
    // the annotation engine
    private VariantAnnotatorEngine annotationEngine;
    // the INFO field annotation key names to remove
    private final List<String> infoFieldAnnotationKeyNamesToRemove = Arrays.asList(GVCFWriter.GVCF_BLOCK, GATKVCFConstants.HAPLOTYPE_SCORE_KEY,
            GATKVCFConstants.INBREEDING_COEFFICIENT_KEY, GATKVCFConstants.MLE_ALLELE_COUNT_KEY,
            GATKVCFConstants.MLE_ALLELE_FREQUENCY_KEY, GATKVCFConstants.EXCESS_HET_KEY);

    private List<VariantContextBuilder> homRefBlockBuffer = new ArrayList<>(10);  //10 is a generous estimate for the number of overlapping deletions
    private String currentContig;
    private VariantContextWriter vcfWriter;
    private CachingIndexedFastaSequenceFile referenceReader;
    private VariantContextBuilderComparator refBufferComparator = new VariantContextBuilderComparator();

    @Override
    public boolean useVariantAnnotations() { return true;}

    @Override
    public List<Class<? extends Annotation>> getDefaultVariantAnnotationGroups() {
        return Arrays.asList(StandardAnnotation.class, AS_StandardAnnotation.class);
    }

    @Override
    public boolean requiresReference() {return true;}

    @Override
    public void onTraversalStart() {
        VCFHeader inputHeader = getHeaderForVariants();
        if (inputHeader.getGenotypeSamples().size() > 1) {
            throw new UserException.BadInput("ReblockGVCF is a single sample tool, but the input GVCF has more than 1 sample.");
        }
        final Set<VCFHeaderLine> inputHeaders = inputHeader.getMetaDataInSortedOrder();

        final Set<VCFHeaderLine> headerLines = new HashSet<>(inputHeaders);
        // Remove GCVFBlocks, legacy headers, and annotations that aren't informative for single samples
        headerLines.removeIf(vcfHeaderLine -> vcfHeaderLine.getKey().startsWith(GVCFWriter.GVCF_BLOCK) ||
                (vcfHeaderLine.getKey().equals("INFO")) && ((VCFInfoHeaderLine)vcfHeaderLine).getID().equals(GATKVCFConstants.RAW_RMS_MAPPING_QUALITY_DEPRECATED) ||  //remove old (maybe wrong type) and add new with deprecated note
                (vcfHeaderLine.getKey().equals("INFO")) && infoFieldAnnotationKeyNamesToRemove.contains(((VCFInfoHeaderLine)vcfHeaderLine).getID()));

        headerLines.addAll(getDefaultToolVCFHeaderLines());

        genotypingEngine = createGenotypingEngine(new IndexedSampleList(inputHeader.getGenotypeSamples()));
        createAnnotationEngine();

        headerLines.addAll(annotationEngine.getVCFAnnotationDescriptions(false));
        headerLines.add(VCFStandardHeaderLines.getInfoLine(VCFConstants.DEPTH_KEY));   // needed for gVCFs without DP tags
        headerLines.add(GATKVCFHeaderLines.getInfoLine(GATKVCFConstants.RAW_QUAL_APPROX_KEY));
        headerLines.add(GATKVCFHeaderLines.getInfoLine(GATKVCFConstants.AS_RAW_QUAL_APPROX_KEY));
        headerLines.add(GATKVCFHeaderLines.getInfoLine(GATKVCFConstants.VARIANT_DEPTH_KEY));
        headerLines.add(GATKVCFHeaderLines.getInfoLine(GATKVCFConstants.AS_VARIANT_DEPTH_KEY));
        headerLines.add(GATKVCFHeaderLines.getInfoLine(GATKVCFConstants.RAW_GENOTYPE_COUNT_KEY));
        headerLines.add(GATKVCFHeaderLines.getInfoLine(GATKVCFConstants.RAW_MAPPING_QUALITY_WITH_DEPTH_KEY));
        headerLines.add(GATKVCFHeaderLines.getInfoLine(GATKVCFConstants.MAPPING_QUALITY_DEPTH_DEPRECATED));  //NOTE: this is deprecated, but keep until we reprocess all GVCFs
        if (inputHeader.hasInfoLine(GATKVCFConstants.RAW_RMS_MAPPING_QUALITY_DEPRECATED)) {
            headerLines.add(GATKVCFHeaderLines.getInfoLine(GATKVCFConstants.RAW_RMS_MAPPING_QUALITY_DEPRECATED));
        }

        if ( dbsnp.dbsnp != null  ) {
            VCFStandardHeaderLines.addStandardInfoLines(headerLines, true, VCFConstants.DBSNP_KEY);
        }

        VariantContextWriter writer = createVCFWriter(outputFile);

        try {
            vcfWriter = new GVCFWriter(writer, new ArrayList<Number>(GVCFGQBands), PLOIDY_TWO, floorBlocks);
        } catch ( IllegalArgumentException e ) {
            throw new IllegalArgumentException("GQBands are malformed: " + e.getMessage(), e);
        }
        vcfWriter.writeHeader(new VCFHeader(headerLines, inputHeader.getGenotypeSamples()));

        if (genotypeArgs.samplePloidy != PLOIDY_TWO) {
            throw new UserException.BadInput("The -ploidy parameter is ignored in " + getClass().getSimpleName() + " tool as this is tool assumes a diploid sample");
        }

         referenceReader = ReferenceUtils.createReferenceReader(referenceArguments.getReferenceSpecifier());
    }

    private HaplotypeCallerGenotypingEngine createGenotypingEngine(SampleList samples) {
        final HaplotypeCallerArgumentCollection hcArgs = new HaplotypeCallerArgumentCollection();
        // create the genotyping engine
        hcArgs.standardArgs.outputMode = OutputMode.EMIT_ALL_ACTIVE_SITES;
        hcArgs.standardArgs.annotateAllSitesWithPLs = true;
        hcArgs.standardArgs.genotypeArgs = genotypeArgs.clone();
        hcArgs.emitReferenceConfidence = ReferenceConfidenceMode.GVCF;   //this is important to force emission of all alleles at a multiallelic site
        return new HaplotypeCallerGenotypingEngine(hcArgs, samples, true, false);

    }

    @VisibleForTesting
    protected void createAnnotationEngine() {
        annotationEngine = new VariantAnnotatorEngine(makeVariantAnnotations(), dbsnp.dbsnp, Collections.emptyList(), false, false);
    }

    // get VariantContexts from input gVCFs and regenotype
    @Override
    public void apply(VariantContext variant, ReadsContext reads, ReferenceContext ref, FeatureContext features) {
        if (currentContig == null) {
            currentContig = variant.getContig(); //variantContexts should have identical start, so choose 0th arbitrarily
        } else if (!variant.getContig().equals(currentContig)) {
            flushRefBlockBuffer();
            currentContig = variant.getContig();
            vcfOutputEnd = 0;
        }
        if (variant.getStart() > vcfOutputEnd) {
            isInDeletion = false;
        }
        final VariantContext newVC;
        try {
            newVC = regenotypeVC(variant);
        } catch (Exception e) {
            throw new GATKException("Exception thrown at " + variant.getContig() + ":" + variant.getStart() + " " + variant.toString(), e);
        }
        if (newVC != null) {
            try {
                vcfWriter.add(newVC);
                vcfOutputEnd = newVC.getEnd();
            } catch (Exception e) {
                throw new GATKException("Exception thrown at " + newVC.getContig() + ":" + newVC.getStart() + " " + newVC.toString(), e);
            }

            /*if (newVC.getReference().length() > 1) {
                if (vcfOutputEnd < newVC.getStart() + newVC.getReference().length() - 1) {
                    vcfOutputEnd = newVC.getStart() + newVC.getReference().length() - 1;
                }
            }*/
        }
    }

    @Override
    public Object onTraversalSuccess() {
        flushRefBlockBuffer();
        return null;
    }

    /**
     * Re-genotype (and re-annotate) a VariantContext
     * Note that the GVCF write takes care of the actual homRef block merging based on {@code GVCFGQBands}
     *
     * @param originalVC     the combined genomic VC
     * @return a new VariantContext or null if the site turned monomorphic (may have been added to ref block buffer)
     */
    private VariantContext regenotypeVC(final VariantContext originalVC) {
        VariantContext result = originalVC;

        //Pass back ref-conf homRef sites/blocks to be combined by the GVCFWriter
        if (isHomRefBlock(result)) {
            if (result.getEnd() <= vcfOutputEnd) {
                return null;
            }
            final VariantContext filtered = filterHomRefBlock(result);
            if (filtered != null) {
                updateHomRefBlockBuffer(filtered);
            }
            return null;
        }

        //don't need to calculate quals for sites with no data whatsoever or sites already genotyped homRef,
        // but if STAND_CALL_CONF > 0 we need to drop low quality alleles and regenotype
        //Note that spanning deletion star alleles will be considered low quality
        if (result.getAttributeAsInt(VCFConstants.DEPTH_KEY, 0) > 0 && !isMonomorphicCallWithAlts(result) && dropLowQuals) {
            result = genotypingEngine.calculateGenotypes(originalVC);
        }
        if (result == null) {
            return null;
        }



        //variants with PL[0] less than threshold get turned to homRef with PL=[0,0,0], shouldn't get INFO attributes
        //make sure we can call het variants with GQ >= rgqThreshold in joint calling downstream
        if(shouldBeReblocked(result)) {
            if (result.getEnd() <= vcfOutputEnd) {
                return null;
            }
            final VariantContextBuilder newHomRefBuilder = lowQualVariantToGQ0HomRef(result, originalVC);
            if (newHomRefBuilder != null) {
                updateHomRefBlockBuffer(newHomRefBuilder.make());
            }
            return null;  //don't write yet in case new ref block needs to be modified
        }
        //high quality variant
        else {
            final VariantContext trimmedVariant = cleanUpHighQualityVariant(result, originalVC);
            //handle overlapping deletions so there are no duplicate bases
            //this is under the assumption that HaplotypeCaller doesn't give us overlapping bases, which isn't really tru

            if (homRefBlockBuffer.size() > 0) {
                //Queue the low quality deletions until we hit a high quality variant or the start is past the oldBlockEnd
                //Oh no, do we have to split the deletion-ref blocks???
                //For variants inside spanning deletion, * likelihood goes to zero?  Or matches ref?
                updateHomRefBlockBuffer(trimmedVariant);
            }
            return trimmedVariant;
        }
    }

    /**
     * Write and remove ref blocks that end before the variant
     * Trim ref block if the variant occurs in the middle of a block
     * @param variantContextToOutput can overlap existing ref blocks in buffer, but should never start before vcfOutputEnd
     */
    private void updateHomRefBlockBuffer(final VariantContext variantContextToOutput) {
        Utils.validate(variantContextToOutput.getStart() <= variantContextToOutput.getEnd(),
                "Input variant context at position " + currentContig + ":" + variantContextToOutput.getStart() + " has negative length: start=" + variantContextToOutput.getStart() + " end=" + variantContextToOutput.getEnd());
        if (variantContextToOutput.getGenotype(0).isHomRef() && variantContextToOutput.getStart() < vcfOutputEnd) {
            throw new IllegalStateException("Reference positions added to buffer should not overlap positions already output to VCF. "
                    + variantContextToOutput.getStart() + " overlaps position " + currentContig + ":" + vcfOutputEnd + " already emitted.");
        }
        final List<VariantContextBuilder> completedBlocks = new ArrayList<>();
        final List<VariantContextBuilder> tailBuffer = new ArrayList<>();
        for (final VariantContextBuilder builder : homRefBlockBuffer) {
            final int blockStart = (int)builder.getStart();
            final int variantEnd = variantContextToOutput.getEnd();
            if (blockStart > variantEnd) {
                break;
            }
            int blockEnd = (int)builder.getStop();
            final int variantStart = variantContextToOutput.getStart();
            if (blockEnd >= variantStart) {  //then trim out overlap
                if (blockEnd > variantEnd && blockStart <= variantStart) {  //then this block will be split -- add a post-variant block
                    final VariantContextBuilder blockTailBuilder = new VariantContextBuilder(builder);
                    moveBuilderStart(blockTailBuilder, variantEnd + 1);
                    tailBuffer.add(blockTailBuilder);
                    builder.stop(variantEnd);
                    builder.attribute(VCFConstants.END_KEY, variantEnd);
                    blockEnd = variantEnd;
                }
                if (blockStart < variantStart) { //right trim
                    if (blockStart > variantStart - 1) {
                        throw new GATKException.ShouldNeverReachHereException("ref blocks screwed up; current builder: " + builder.getStart() + " to " + builder.getStop());
                    }
                    builder.attribute(VCFConstants.END_KEY, variantStart - 1);
                    builder.stop(variantStart - 1);
                } else {  //left trim
                    if (variantContextToOutput.contains(new SimpleInterval(currentContig, blockStart, blockEnd))) {
                        completedBlocks.add(builder);
                    } else {
                        if (blockEnd < variantEnd + 1) {
                            throw new GATKException.ShouldNeverReachHereException("ref blocks screwed up; current builder: " + builder.getStart() + " to " + builder.getStop());
                        }
                        final byte[] newRef = ReferenceUtils.getRefBaseAtPosition(ReferenceUtils.createReferenceReader(referenceArguments.getReferenceSpecifier()), variantContextToOutput.getContig(), variantEnd + 1);
                        moveBuilderStart(builder, variantEnd + 1);
                    }
                }
                if (builder.getStart() > builder.getStop()) {
                    throw new GATKException.ShouldNeverReachHereException("ref blocks screwed up; current builder: " + builder.getStart() + " to " + builder.getStop());
                }
            }
            //only flush ref blocks if we're outputting a variant, otherwise ref blocks can be out of order
            if (builder.getStart() < variantStart && !variantContextToOutput.getGenotype(0).isHomRef()) {
                vcfWriter.add(builder.make());
                vcfOutputEnd = (int)builder.getStop();
                completedBlocks.add(builder);
            }
            bufferEnd = blockEnd;  //keep track of observed ends
        }
        homRefBlockBuffer.removeAll(completedBlocks);
        if (variantContextToOutput.getGenotype(0).isHomRef()) {
            final VariantContextBuilder newHomRefBlock = new VariantContextBuilder(variantContextToOutput);
            homRefBlockBuffer.add(newHomRefBlock);
        }
        homRefBlockBuffer.addAll(tailBuffer);
        homRefBlockBuffer.sort(refBufferComparator);  //this may seem lazy, but it's more robust to assumptions about overlap being violated
        bufferEnd = Math.max(bufferEnd, variantContextToOutput.getEnd());
    }

    private void flushRefBlockBuffer() {
         for (final VariantContextBuilder builder : homRefBlockBuffer) {
             vcfWriter.add(builder.make());
             vcfOutputEnd = (int)builder.getStop();
         }
         homRefBlockBuffer.clear();
         bufferEnd = 0;
    }

   /**
     * Gets the reference base to start a new block based on bufferEnd
     * @param originalRef
     * @param originalStart
     * @return
     */
    private Allele getRefAfterTrimmedDeletion(final Allele originalRef, final int originalStart, final int newStart) {
        final byte[] originalBases = originalRef.getBases();
        final int firstBaseToKeep = newStart - originalStart;
        final byte[] newBases = Arrays.copyOfRange(originalBases, firstBaseToKeep, firstBaseToKeep+1);
        return Allele.create(newBases, true);
    }

    private boolean isHomRefBlock(final VariantContext result) {
        return result.getAlternateAlleles().contains(Allele.NON_REF_ALLELE) && result.hasAttribute(VCFConstants.END_KEY);
    }

    /**
     * determine if VC is a homRef "call", i.e. an annotated variant with non-symbolic alt alleles and homRef genotypes
     * we treat these differently from het/homVar calls or homRef blocks
     * @param result VariantContext to process
     * @return true if VC is a 0/0 call and not a homRef block
     */
    private boolean isMonomorphicCallWithAlts(final VariantContext result) {
        final Genotype genotype = result.getGenotype(0);
        return ((genotype.isHomRef() || genotype.isNoCall()) && result.getLog10PError() != VariantContext.NO_LOG10_PERROR)
                || genotype.getAlleles().stream().allMatch(a -> a.equals(Allele.SPAN_DEL) || a.isReference() || a.isNoCall());
    }

    private VariantContext filterHomRefBlock(final VariantContext result) {
        final Genotype genotype = result.getGenotype(0);
        /*if (result.getEnd() <= bufferEnd) {
            return null;
        }*/
        final VariantContextBuilder vcBuilder = new VariantContextBuilder(result);
        if (result.getStart() <= vcfOutputEnd) {
            if (result.getEnd() <= vcfOutputEnd) {
                return null;
            }
            moveBuilderStart(vcBuilder, vcfOutputEnd + 1);
        }
        if (dropLowQuals && (!genotype.hasGQ() || genotype.getGQ() < rgqThreshold || genotype.getGQ() == 0)) {
            return null;
        } else if (genotype.isCalled() && genotype.isHomRef()) {
            if (!genotype.hasPL()) {
                if (genotype.hasGQ()) {
                    logger.warn("PL is missing for hom ref genotype at at least one position for sample " + genotype.getSampleName() + ": " + result.getContig() + ":" + result.getStart() +
                            ".  Using GQ to determine quality.");
                    final int gq = genotype.getGQ();
                    final GenotypeBuilder gBuilder = new GenotypeBuilder(genotype);
                    vcBuilder.genotypes(gBuilder.GQ(gq).make());
                    return vcBuilder.make();
                } else {
                    final String message = "Homozygous reference genotypes must contain GQ or PL. Both are missing for hom ref genotype at "
                            + result.getContig() + ":" + result.getStart() + " for sample " + genotype.getSampleName() + ".";
                    if (allowMissingHomRefData) {
                        logger.warn(message);
                        final GenotypeBuilder gBuilder = new GenotypeBuilder(genotype);
                        vcBuilder.genotypes(gBuilder.GQ(0).PL(new int[]{0,0,0}).make());
                        return vcBuilder.make();
                    } else {
                        throw new UserException.BadInput(message);
                    }
                }
            }
            return vcBuilder.make();
        } else if (!genotype.isCalled() && genotype.hasPL() && genotype.getPL()[0] == 0) {
            return vcBuilder.make();
        }
        else {
            return null;
        }
    }

    @VisibleForTesting
    protected boolean shouldBeReblocked(final VariantContext result) {
        final Genotype genotype = result.getGenotype(0);
        return !genotype.isCalled() || (genotype.hasPL() && getGenotypeLikelihoodsOrPosteriors(genotype, posteriorsKey)[0] < rgqThreshold) || genotype.isHomRef()
                || !genotypeHasConcreteAlt(genotype)
                || genotype.getAlleles().stream().anyMatch(a -> a.equals(Allele.NON_REF_ALLELE));
    }

    private boolean genotypeHasConcreteAlt(final Genotype g) {
        return g.getAlleles().stream().anyMatch(a -> !a.isReference() && !a.isSymbolic() && !a.equals(Allele.SPAN_DEL));
    }

    /**
     * "reblock" a variant by converting its genotype to homRef, changing PLs, adding reblock END tags and other attributes
     * @param result  a variant already determined to be low quality
     * @param originalVC the variant context with the original, full set of alleles
     * @return
     */
    @VisibleForTesting
    public VariantContextBuilder lowQualVariantToGQ0HomRef(final VariantContext result, final VariantContext originalVC) {
        //Utils.validate(result.getEnd() > vcfOutputEnd && result.getEnd() > bufferEnd, "Variant is entirely "
        //        + "overlapped by emitted deletions and/or pending reference blocks.");
        // this is okay because trimmed deletions will have tail blocks

        if(dropLowQuals && (!isMonomorphicCallWithAlts(result) || !result.getGenotype(0).isCalled())) {
            return null;
        }

        if (!result.getGenotype(0).isCalled() && !result.getGenotype(0).hasPL()) {
            final Map<String, Object> blockAttributes = new LinkedHashMap<>(2);
            final GenotypeBuilder gb = changeCallToGQ0HomRef(result, blockAttributes);
            final List<Allele> blockAlleles = Arrays.asList(Allele.create(result.getReference().getBases()[0], true), Allele.NON_REF_ALLELE);
            final VariantContextBuilder vb = new VariantContextBuilder(result).alleles(blockAlleles).attributes(blockAttributes).genotypes(gb.make())
                    .log10PError(VariantContext.NO_LOG10_PERROR);
            if (vcfOutputEnd + 1 > result.getStart()) {
                moveBuilderStart(vb, vcfOutputEnd + 1);
            }
            return vb;   //delete variant annotations and add end key
        }

        final Map<String, Object> attrMap = new HashMap<>();

        //this method does a lot of things, including fixing alleles and adding the END key
        final GenotypeBuilder gb = changeCallToGQ0HomRef(result, attrMap);  //note that gb has all zero PLs

        //there are some cases where there are low quality variants with homRef calls AND alt alleles!
        //take the most likely alt's likelihoods when making the ref block
        if (isMonomorphicCallWithAlts(result)) {
            final Genotype genotype = result.getGenotype(0);
            final List<Allele> bestAlleles = AlleleSubsettingUtils.calculateMostLikelyAlleles(result, PLOIDY_TWO, 1);
            final Optional<Allele> bestNonSymbolicAlt = bestAlleles.stream().filter(a -> !a.isReference() && !a.isNonRefAllele()).findFirst();  //allow span dels
            //here we're assuming that an alt that isn't <NON_REF> will have higher likelihood than non-ref, which should be true
            final Allele bestAlt = bestNonSymbolicAlt.isPresent() ? bestNonSymbolicAlt.get() : Allele.NON_REF_ALLELE;
            final int[] idxVector = result.getGLIndicesOfAlternateAllele(bestAlt);   //this is always length 3
            if ((posteriorsKey != null && genotype.hasExtendedAttribute(posteriorsKey)) || genotype.hasPL()) {
                final int[] multiallelicPLs = getGenotypeLikelihoodsOrPosteriors(genotype, posteriorsKey);
                int[] newPLs = new int[3];
                newPLs[0] = multiallelicPLs[idxVector[0]];  //in the case of *, we need to renormalize to homref
                newPLs[1] = multiallelicPLs[idxVector[1]];
                newPLs[2] = multiallelicPLs[idxVector[2]];
                if (newPLs[0] != 0) {
                    final int[] output = new int[newPLs.length];
                    for (int i = 0; i < newPLs.length; i++) {
                        output[i] = Math.max(newPLs[i] - newPLs[0], 0);
                    }
                    newPLs = output;
                }
                gb.PL(newPLs);
                gb.GQ(MathUtils.secondSmallestMinusSmallest(newPLs, 0));
            }
            if (genotype.hasAD()) {
                final int depth = (int) MathUtils.sum(genotype.getAD());
                gb.DP(depth);
                gb.attribute(GATKVCFConstants.MIN_DP_FORMAT_KEY, depth);
            }
        }

        final VariantContextBuilder builder = new VariantContextBuilder(result);

        final Genotype newG = gb.make();
        builder.alleles(Arrays.asList(newG.getAlleles().get(0), Allele.NON_REF_ALLELE)).genotypes(newG);
        if (result.getStart() <= vcfOutputEnd) {
            final int newStart = vcfOutputEnd + 1;
            moveBuilderStart(builder, newStart);
        }
        return builder.unfiltered()
                .log10PError(VariantContext.NO_LOG10_PERROR).attributes(attrMap); //genotyping engine will add lowQual filter, so strip it off
    }

    /**
     * Note that this modifies {@code attrMap} as a side effect
     * @param result a VC to be converted to a GQ0 homRef call
     * @param attrMap the new VC attribute map, to update the END tag as necessary
     * @return a GenotypeBuilder to make a 0/0 call with PLs=[0,0,0]
     */
    @VisibleForTesting
    protected GenotypeBuilder changeCallToGQ0HomRef(final VariantContext result, final Map<String, Object> attrMap) {
        Genotype genotype = result.getGenotype(0);
        Allele newRef = result.getReference();
        GenotypeBuilder gb = new GenotypeBuilder(genotype);
        //NB: If we're dropping a deletion allele, then we need to trim the reference and add an END tag with the vc stop position
        if (result.getReference().length() > 1 || genotype.getAlleles().contains(Allele.SPAN_DEL) || genotype.getAlleles().contains(Allele.NO_CALL)) {
            newRef = Allele.create(newRef.getBases()[0], true);
            gb.alleles(Collections.nCopies(PLOIDY_TWO, newRef)).PL(new int[3]).GQ(0).noAD().noAttributes();
        }
        //if GT is not homRef, correct it
        if (!isMonomorphicCallWithAlts(result)) {
            gb.PL(new int[3]);  //3 for diploid PLs, automatically initializes to zero
            gb.GQ(0).noAD().alleles(Collections.nCopies(PLOIDY_TWO, newRef)).noAttributes();
        }
        attrMap.put(VCFConstants.END_KEY, result.getEnd());
        return gb;
    }

    @VisibleForTesting
    protected VariantContext cleanUpHighQualityVariant(final VariantContext result, final VariantContext originalVC) {
        Map<String, Object> attrMap = new HashMap<>();
        Map<String, Object> origMap = originalVC.getAttributes();

        final Genotype genotype = result.getGenotype(0);
        VariantContextBuilder builder = new VariantContextBuilder(result);  //QUAL from result is carried through
        builder.attributes(attrMap);

        //always drop alleles that aren't called to reduce PL size
        boolean allelesNeedSubsetting = result.getNAlleles() != originalVC.getNAlleles();
        List<Allele> allelesToDrop = new ArrayList<>();
        for (final Allele currAlt : result.getAlternateAlleles()) {
            boolean foundMatch = false;
            for (final Allele gtAllele : genotype.getAlleles()) {
                if (gtAllele.equals(currAlt, false)) {
                    foundMatch = true;
                    break;
                }
                /*if (gtAllele.equals(Allele.NON_REF_ALLELE)) {
                    if (dropLowQuals) { //don't regenotype, just drop it -- this is a GQ 0 case if ever I saw one
                        return null;
                    } else {
                        GenotypeBuilder gb = changeCallToGQ0HomRef(result, attrMap);
                        final Genotype g = gb.make();
                        return builder.alleles(Arrays.asList(g.getAllele(0), Allele.NON_REF_ALLELE)).unfiltered().log10PError(VariantContext.NO_LOG10_PERROR).attributes(attrMap).genotypes(gb.make()).make();
                    }
                }*/
            }
            if (!foundMatch && !currAlt.isSymbolic()) {
                allelesNeedSubsetting = true;
                allelesToDrop.add(currAlt);
            }
        }

        //We'll trim indels later on in subsetting if necessary

        int[] relevantIndices = new int[result.getNAlleles()];
        final List<Allele> newAlleleSet = new ArrayList<>(result.getAlleles());
        if(allelesNeedSubsetting && !keepAllAlts) {
            newAlleleSet.removeAll(allelesToDrop);
            final GenotypesContext gc = AlleleSubsettingUtils.subsetAlleles(result.getGenotypes(), PLOIDY_TWO, result.getAlleles(), newAlleleSet, null, GenotypeAssignmentMethod.USE_PLS_TO_ASSIGN, result.getAttributeAsInt(VCFConstants.DEPTH_KEY, 0));
            //note that subsetting alleles can increase GQ, e.g. with one confident reference allele and a deletion allele that's either 4 or 5 bases long
            builder.genotypes(gc).alleles(newAlleleSet);
            final VariantContext newTrimmedAllelesVC = GATKVariantContextUtils.trimAlleles(builder.make(), false, true);
            builder = new VariantContextBuilder(newTrimmedAllelesVC);
            //save indices of new alleles for annotation processing
            relevantIndices = newAlleleSet.stream().mapToInt(a -> originalVC.getAlleles().indexOf(a)).toArray();

            //if deletion needs trimming, fill in the gap with a ref block
            final int oldLongestAlleleLength = result.getReference().length();
            final int newLongestAlleleLength = newTrimmedAllelesVC.getReference().length();
            if (newLongestAlleleLength < oldLongestAlleleLength && genotype.hasPL()) {
                final Allele oldLongestDeletion;
                try {
                    oldLongestDeletion = allelesToDrop.stream().filter(a -> !a.equals(Allele.SPAN_DEL)).min(Allele::compareTo).orElseThrow(NoSuchElementException::new);
                } catch (Exception e) {
                    throw new GATKException("No longest deletion at " + result.getStart() + " with alleles to drop: " + allelesToDrop);
                }
                //need to add a ref block to make up for the allele trimming or there will be a hole in the GVCF
                //subset PLs to ref and longest dropped allele (longest may not be most likely, but we'll approximate so we don't have to make more than one ref block)
                final int[] originalLikelihoods = getGenotypeLikelihoodsOrPosteriors(genotype, posteriorsKey);
                final int[] longestVersusRefPLIndices = AlleleSubsettingUtils.subsettedPLIndices(result.getGenotype(0).getPloidy(),
                        result.getAlleles(), Arrays.asList(result.getReference(), oldLongestDeletion));
                final int[] newRefBlockLikelihoods = MathUtils.normalizePLs(Arrays.stream(longestVersusRefPLIndices)
                                .map(idx -> originalLikelihoods[idx]).toArray());
                if (newRefBlockLikelihoods[0] != 0) {
                    for (int i = 0; i < newRefBlockLikelihoods.length; i++) {
                        newRefBlockLikelihoods[i] = Math.max(newRefBlockLikelihoods[i]-newRefBlockLikelihoods[0], 0);
                    }
                }
                final GenotypeBuilder refBlockGenotypeBuilder = new GenotypeBuilder();
                //final int refStart = result.getEnd()-(oldLongestAlleleLength-newLongestAlleleLength)+1;
                final int refStart = Math.max(result.getEnd()-(oldLongestAlleleLength-newLongestAlleleLength), vcfOutputEnd)+1;
                //final Allele newRef = getRefAfterTrimmedDeletion(result.getReference(), result.getStart(), refStart);
                final Allele newRef = Allele.create(ReferenceUtils.getRefBaseAtPosition(referenceReader, result.getContig(), refStart), true);
                refBlockGenotypeBuilder.PL(newRefBlockLikelihoods)
                        .GQ(MathUtils.secondSmallestMinusSmallest(newRefBlockLikelihoods, 0))
                        .alleles(Arrays.asList(newRef, newRef));
                if (genotype.hasDP()) {
                    refBlockGenotypeBuilder.DP(genotype.getDP());
                }
                //if (refStart > vcfOutputEnd  && newTrimmedAllelesVC.getEnd() > vcfOutputEnd) {
                if (refStart > vcfOutputEnd && result.getEnd() > vcfOutputEnd) {
                    final VariantContextBuilder trimBlockBuilder = new VariantContextBuilder();
                    trimBlockBuilder.chr(currentContig).start(Math.max(refStart, vcfOutputEnd+1)).stop(result.getEnd()).
                            alleles(Arrays.asList(newRef, Allele.NON_REF_ALLELE)).attribute(VCFConstants.END_KEY, result.getEnd())
                            .genotypes(refBlockGenotypeBuilder.make());
                    updateHomRefBlockBuffer(trimBlockBuilder.make());
                }
            }
        }

        //remove any AD reads for the non-ref
        final VariantContext updatedAlleles = builder.make();
        final Genotype g;
        int nonRefInd = updatedAlleles.getAlleleIndex(Allele.NON_REF_ALLELE);
        final ArrayList<Genotype> genotypesArray = new ArrayList<>();
        g = updatedAlleles.getGenotype(0);
        if (g.hasAD() && nonRefInd != -1) {
            int[] ad = g.getAD();
            if (ad.length >= nonRefInd && ad[nonRefInd] > 0) { //only initialize a builder if we have to
                GenotypeBuilder gb = new GenotypeBuilder(g);
                ad[nonRefInd] = 0;
                gb.AD(ad).DP((int) MathUtils.sum(ad));
                genotypesArray.add(gb.make());
            } else {
                genotypesArray.add(g);
            }
        } else {
            genotypesArray.add(g);
        }

        builder.genotypes(genotypesArray);

        //all VCs should get new RAW_MAPPING_QUALITY_WITH_DEPTH_KEY, but preserve deprecated keys if present
        if (!originalVC.hasAttribute(GATKVCFConstants.RAW_MAPPING_QUALITY_WITH_DEPTH_KEY)) {
            //we're going to approximate depth for MQ calculation with the site-level DP (should be informative and uninformative reads), which is pretty safe because it will only differ if reads are missing MQ
            final Integer rawMqValue = originalVC.hasAttribute(GATKVCFConstants.RAW_RMS_MAPPING_QUALITY_DEPRECATED) ?
                    (int)Math.round(originalVC.getAttributeAsDouble(GATKVCFConstants.RAW_RMS_MAPPING_QUALITY_DEPRECATED, 0.0)) :
                    (int)Math.round(originalVC.getAttributeAsDouble(VCFConstants.RMS_MAPPING_QUALITY_KEY, 60.0) *
                            originalVC.getAttributeAsDouble(VCFConstants.RMS_MAPPING_QUALITY_KEY, 60.0) *
                            originalVC.getAttributeAsInt(VCFConstants.DEPTH_KEY, 0));
            attrMap.put(GATKVCFConstants.RAW_MAPPING_QUALITY_WITH_DEPTH_KEY,
                    String.format("%d,%d", rawMqValue, originalVC.getAttributeAsInt(VCFConstants.DEPTH_KEY, 0)));
            if (originalVC.hasAttribute(GATKVCFConstants.RAW_RMS_MAPPING_QUALITY_DEPRECATED)) {
                attrMap.put(GATKVCFConstants.RAW_RMS_MAPPING_QUALITY_DEPRECATED, originalVC.getAttributeAsDouble(GATKVCFConstants.RAW_RMS_MAPPING_QUALITY_DEPRECATED, 0)); //NOTE: this annotation is deprecated, but keep it here so we don't have to reprocess gnomAD v3 GVCFs again
                attrMap.put(GATKVCFConstants.MAPPING_QUALITY_DEPTH_DEPRECATED, originalVC.getAttributeAsInt(VCFConstants.DEPTH_KEY, 0)); //NOTE: this annotation is deprecated, but keep it here so we don't have to reprocess gnomAD v3 GVCFs again
            }
        }

        //copy over info annotations
        for(final InfoFieldAnnotation annotation : annotationEngine.getInfoAnnotations()) {
            for (final String key : annotation.getKeyNames()) {
                if (infoFieldAnnotationKeyNamesToRemove.contains(key)) {
                    continue;
                }
                if (origMap.containsKey(key)) {
                    attrMap.put(key, origMap.get(key));
                }
            }
            if (annotation instanceof ReducibleAnnotation) {
                for (final String rawKey : ((ReducibleAnnotation)annotation).getRawKeyNames()) {
                    if (infoFieldAnnotationKeyNamesToRemove.contains(rawKey)) {
                        continue;
                    }
                    if (origMap.containsKey(rawKey)) {
                        if (allelesNeedSubsetting && AnnotationUtils.isAlleleSpecific(annotation)) {
                            List<String> alleleSpecificValues = AnnotationUtils.getAlleleLengthListOfString(originalVC.getAttributeAsString(rawKey, null));
                            final List<?> subsetList = alleleSpecificValues.size() > 0 ? AlleleSubsettingUtils.remapRLengthList(alleleSpecificValues, relevantIndices, "")
                                    : Collections.nCopies(relevantIndices.length, "");
                            attrMap.put(rawKey, AnnotationUtils.encodeAnyASListWithRawDelim(subsetList));
                        } else {
                            attrMap.put(rawKey, origMap.get(rawKey));
                        }
                    }
                }
            }
        }
        //do QUAL calcs after we potentially drop alleles
        if (doQualApprox) {
            if ((posteriorsKey != null && g.hasExtendedAttribute(posteriorsKey)) || g.hasPL()) {
                int[] plsMaybeUnnormalized;
                if (updatedAlleles.getAlternateAlleles().contains(Allele.SPAN_DEL)) {
                    final List<Allele> altsWithoutStar = new ArrayList<>(updatedAlleles.getAlleles());
                    altsWithoutStar.remove(Allele.SPAN_DEL);
                    if (altsWithoutStar.stream().anyMatch(a -> !a.isReference() && !a.isSymbolic())) {
                        final int[] subsettedPLIndices = AlleleSubsettingUtils.subsettedPLIndices(PLOIDY_TWO, updatedAlleles.getAlleles(), altsWithoutStar);
                        final int[] oldPLs = getGenotypeLikelihoodsOrPosteriors(g, posteriorsKey);
                        plsMaybeUnnormalized = Arrays.stream(subsettedPLIndices).map(idx -> oldPLs[idx]).toArray();
                        attrMap.put(GATKVCFConstants.RAW_QUAL_APPROX_KEY, plsMaybeUnnormalized[0] - MathUtils.arrayMin(plsMaybeUnnormalized));
                    } else {
                        attrMap.put(GATKVCFConstants.RAW_QUAL_APPROX_KEY, 0);
                    }
                } else {
                    attrMap.put(GATKVCFConstants.RAW_QUAL_APPROX_KEY, getGenotypeLikelihoodsOrPosteriors(g, posteriorsKey)[0]);
                }
                int varDP = QualByDepth.getDepth(result.getGenotypes(), null);
                if (varDP == 0) {  //prevent QD=Infinity case
                    varDP = originalVC.getAttributeAsInt(VCFConstants.DEPTH_KEY, 1); //if there's no VarDP and no DP, just prevent Infs/NaNs and QD will get capped later
                }
                attrMap.put(GATKVCFConstants.VARIANT_DEPTH_KEY, varDP);
                if (annotationEngine.getInfoAnnotations().stream()
                        .anyMatch(infoFieldAnnotation -> infoFieldAnnotation.getClass().getSimpleName().equals("AS_QualByDepth"))) {
                    final List<String> quals = new ArrayList<>();
                    for (final Allele alt : updatedAlleles.getAlleles()) {
                        if (alt.isReference()) {
                            //GDB expects an empty field for ref
                            continue;
                        }
                        if (alt.equals(Allele.NON_REF_ALLELE) || alt.equals(Allele.SPAN_DEL)) {
                            quals.add("0");
                            continue;
                        }
                        if (g.isHetNonRef()) {
                            final int[] subsetIndices = AlleleSubsettingUtils.subsettedPLIndices(PLOIDY_TWO, updatedAlleles.getAlleles(), Arrays.asList(updatedAlleles.getReference(), alt, Allele.NON_REF_ALLELE));
                            final int[] fullLikelihoods = getGenotypeLikelihoodsOrPosteriors(g, posteriorsKey);
                            final int[] subsetPLs = Arrays.stream(subsetIndices).map(idx -> fullLikelihoods[idx]).toArray();
                            quals.add(Integer.toString(subsetPLs[0] - MathUtils.arrayMin(subsetPLs)));
                        }
                        else if (g.hasPL()) {
                            quals.add(Integer.toString(getGenotypeLikelihoodsOrPosteriors(g, posteriorsKey)[0]));
                        }
                    }
                    attrMap.put(GATKVCFConstants.AS_RAW_QUAL_APPROX_KEY, AnnotationUtils.ALLELE_SPECIFIC_RAW_DELIM + String.join(AnnotationUtils.ALLELE_SPECIFIC_RAW_DELIM, quals));
                    List<Integer> as_varDP = AS_QualByDepth.getAlleleDepths(AlleleSubsettingUtils.subsetAlleles(result.getGenotypes(),
                            HomoSapiensConstants.DEFAULT_PLOIDY, result.getAlleles(), newAlleleSet, null,
                            GenotypeAssignmentMethod.USE_PLS_TO_ASSIGN, result.getAttributeAsInt(VCFConstants.DEPTH_KEY, 0)));
                    if (as_varDP != null) {
                        attrMap.put(GATKVCFConstants.AS_VARIANT_DEPTH_KEY, as_varDP.stream().map(n -> Integer.toString(n)).collect(Collectors.joining(AnnotationUtils.ALLELE_SPECIFIC_RAW_DELIM)));
                    }
                }
            } else {
                final List<String> quals = new ArrayList<>();
                for (final Allele alt : newAlleleSet) {
                    if (alt.isReference()) {
                        //GDB expects an empty field for ref
                        continue;
                    } else {
                        quals.add("0");
                    }
                }
            }
        } else {  //manually copy annotations that might be from reblocking and aren't part of AnnotationEngine
            if (result.hasAttribute(GATKVCFConstants.AS_VARIANT_DEPTH_KEY)) {
                attrMap.put(GATKVCFConstants.AS_VARIANT_DEPTH_KEY, result.getAttribute(GATKVCFConstants.AS_VARIANT_DEPTH_KEY));
            }
            if (result.hasAttribute(GATKVCFConstants.RAW_QUAL_APPROX_KEY)) {
                attrMap.put(GATKVCFConstants.RAW_QUAL_APPROX_KEY, result.getAttribute(GATKVCFConstants.RAW_QUAL_APPROX_KEY));
            }

        }
        attrMap.put(GATKVCFConstants.RAW_GENOTYPE_COUNT_KEY, g.getAlleles().stream().anyMatch(Allele::isReference) ? Arrays.asList(0,1,0) : Arrays.asList(0,0,1)); //ExcessHet currently uses rounded/integer genotype counts, so do the same here

        return builder.attributes(attrMap).unfiltered().make();
    }

    /**
     * Modifies ref block builder to change start position and update ref allele accordingly in VC and genotypes
     * @param builder
     * @param newStart
     */
    private void moveBuilderStart(final VariantContextBuilder builder, final int newStart) {
        final byte[] newRef = ReferenceUtils.getRefBaseAtPosition(referenceReader, currentContig, newStart);
        final Allele newRefAllele = Allele.create(newRef, true);
        final ArrayList<Genotype> genotypesArray = new ArrayList<>();
        for (final Genotype g : builder.getGenotypes()) {
            final GenotypeBuilder gb = new GenotypeBuilder(g);
            final List<Allele> newGTAlleles = g.getAlleles().stream().map(a -> a.isReference() ? newRefAllele : a).collect(Collectors.toList());
            gb.alleles(newGTAlleles);
            genotypesArray.add(gb.make());
        }
        final List<Allele> newVCAlleles = builder.getAlleles().stream().map(a -> a.isReference() ? newRefAllele : a).collect(Collectors.toList());
        builder.start(newStart).alleles(newVCAlleles).genotypes(genotypesArray);
    }

    /**
     *
     * @param genotype
     * @param posteriorsKey may be null
     * @return may be null
     */
    private int[] getGenotypeLikelihoodsOrPosteriors(final Genotype genotype, final String posteriorsKey) {
        if ((posteriorsKey != null && genotype.hasExtendedAttribute(posteriorsKey))) {
            final double[] posteriors = VariantContextGetters.getAttributeAsDoubleArray(genotype, posteriorsKey, () -> null, 0);
            return Arrays.stream(posteriors).mapToInt(x -> (int)Math.round(x)).toArray();
        } else if (genotype.hasPL()) {
            return genotype.getPL();
        } else {
            return null;
        }
    }

    @Override
    public void closeTool() {
        if ( vcfWriter != null ) {
            vcfWriter.close();
        }
    }
}
