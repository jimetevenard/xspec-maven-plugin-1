/**
 * Copyright © 2013, Adam Retter
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     * Neither the name of the <organization> nor the
 *       names of its contributors may be used to endorse or promote products
 *       derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL <COPYRIGHT HOLDER> BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package uk.org.adamretter.maven;

import io.xspec.maven.xspecMavenPlugin.resolver.Resolver;
import io.xspec.maven.xspecMavenPlugin.utils.ProcessedFile;
import net.sf.saxon.s9api.*;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.*;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stream.StreamSource;
import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import net.sf.saxon.Configuration;
import net.sf.saxon.trans.XPathException;
import org.apache.commons.io.output.NullOutputStream;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.project.MavenProject;
import top.marchand.maven.saxon.utils.SaxonOptions;
import top.marchand.maven.saxon.utils.SaxonUtils;

/**
 * Goal which runs any XSpec tests in src/test/xspec
 *
 * @author <a href="mailto:adam.retter@googlemail.com">Adam Retter</a>
 * @author <a href="mailto:christophe@marchand.top">Christophe Marchand</a>
 */
@Mojo(name = "run-xspec", defaultPhase = LifecyclePhase.VERIFY, requiresDependencyResolution = ResolutionScope.TEST)
public class XSpecMojo extends AbstractMojo implements LogProvider {
    public static final transient String XSPEC_PREFIX = "xspec:/";
    public static final transient String CATALOG_NS = "urn:oasis:names:tc:entity:xmlns:xml:catalog";

    @Parameter( defaultValue = "${project}", readonly = true, required = true )
    private MavenProject project;

    @Parameter(property = "skipTests", defaultValue = "false")
    private boolean skipTests;

    /**
     * Location of the XSpec Compiler XSLT i.e. generate-xspec-tests.xsl
     */
    @Parameter(defaultValue = XSPEC_PREFIX+"xspec/compiler/generate-xspec-tests.xsl", required = true)
    private String xspecCompiler;

    /**
     * Location of the XSpec Reporter XSLT i.e. format-xspec-report.xsl
     */
    @Parameter(defaultValue = XSPEC_PREFIX+"xspec/reporter/format-xspec-report.xsl", required = true)
    private String xspecReporter;

    /**
     * Location of the XSpec tests
     */
    @Parameter(defaultValue = "${project.basedir}/src/test/xspec", required = true)
    private File testDir;
    
    @Parameter(name = "saxonOptions")
    private SaxonOptions saxonOptions;

    /**
     * *
     * Exclude various XSpec tests
     */
    @Parameter(alias = "excludes")
    private List<String> excludes;

    /**
     * Location of the XSpec reports
     */
    @Parameter(defaultValue = "${project.build.directory}/xspec-reports", required = true)
    private File reportDir;

    @Parameter(defaultValue = "${catalog.filename}")
    private File catalogFile;

    @Parameter(defaultValue = "${project.build.directory}/surefire-reports", required = true)
    private File surefireReportDir;

    @Parameter(defaultValue = "false")
    private Boolean generateSurefireReport;
    
    @Parameter(defaultValue = "false")
    private Boolean keepGeneratedCatalog;
    
    @Parameter(defaultValue = "${mojoExecution}", readonly = true)
    private MojoExecution execution;

    public static final SAXParserFactory PARSER_FACTORY = SAXParserFactory.newInstance();
    private static final Configuration SAXON_CONFIGURATION = getSaxonConfiguration();
    
    private Processor PROCESSOR = null;

    private XsltCompiler xsltCompiler = null;
    private boolean uriResolverSet = false;
    private List<ProcessedFile> processedFiles;
    private static final List<ProcessedFile> PROCESS_FILES = new ArrayList<>();
    private static final QName INITIAL_TEMPLATE_NAME=QName.fromClarkName("{http://www.jenitennison.com/xslt/xspec}main");
    private static URIResolver initialUriResolver;
    
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        PROCESSOR = new Processor(SAXON_CONFIGURATION);
        if(saxonOptions!=null) {
            try {
                SaxonUtils.prepareSaxonConfiguration(PROCESSOR, saxonOptions);
            } catch(XPathException ex) {
                getLog().error(ex);
                throw new MojoExecutionException("Illegal value in Saxon configuration property", ex);
            }
        }
        xsltCompiler = PROCESSOR.newXsltCompiler();
        if(initialUriResolver==null) {
            initialUriResolver = xsltCompiler.getURIResolver();
        }
        if(saxonOptions!=null) {
            try {
                SaxonUtils.configureXsltCompiler(xsltCompiler, saxonOptions);
            } catch(XPathException ex) {
                getLog().error(ex);
                throw new MojoExecutionException("Illegal value in Saxon configuration property", ex);
            }
        }
        if (!uriResolverSet) {
            try {
                xsltCompiler.setURIResolver(buildUriResolver(initialUriResolver));
                uriResolverSet = true;
            } catch(DependencyResolutionRequiredException | IOException | XMLStreamException ex) {
                getLog().error("while building URIResolver: ",ex);
                throw new MojoExecutionException("while creating URI resolver", ex);
            }
        }
        URIResolver resolver = xsltCompiler.getURIResolver();
        
        if (isSkipTests()) {
            getLog().info("'skipTests' is set... skipping XSpec tests!");
            return;
        }

        final String compilerPath = getXspecCompiler();
        getLog().debug("Using XSpec Compiler: " + compilerPath);

        final String reporterPath = getXspecReporter();
        getLog().debug("Using XSpec Reporter: " + reporterPath);

        Source srcCompiler;
        Source srcReporter;
        try {
            String baseUri = project.getBasedir().toURI().toURL().toExternalForm();
            srcCompiler = resolver.resolve(compilerPath, baseUri);
            if (srcCompiler == null) {
                throw new MojoExecutionException("Could not find XSpec Compiler stylesheets in: " + compilerPath);
            }

            srcReporter = resolver.resolve(reporterPath, baseUri);
            if (srcReporter == null) {
                throw new MojoExecutionException("Could not find XSpec Reporter stylesheets in: " + reporterPath);
            }

            getLog().debug("Compiling "+srcCompiler.getSystemId());
            XsltExecutable xeCompiler = null;
            try {
                xeCompiler = xsltCompiler.compile(srcCompiler);
            } catch(Throwable t) {
                getLog().error("Failed to compile "+srcCompiler.getSystemId(), t);
            }
            getLog().debug("\tCompiled");

            getLog().debug("Compiling "+srcReporter.getSystemId());
            XsltExecutable xeReporter = null;
            try {
                xeReporter = xsltCompiler.compile(srcReporter);
            } catch(Throwable t) {
                getLog().error("Failed to compile "+srcReporter.getSystemId(), t);
            }
            getLog().debug("\tCompiled");
            final XsltTransformer xtReporter = xeReporter.load();
            xtReporter.setParameter(new QName("report-css-uri"), new XdmAtomicValue(RESOURCES_TEST_REPORT_CSS));

            getLog().debug("Looking for XSpecs in: " + getTestDir());
            final List<File> xspecs = findAllXSpecs(getTestDir());
            getLog().info("Found " + xspecs.size() + " XSpecs...");

            XsltExecutable xeSurefire = null;
            if (generateSurefireReport) {
                XsltCompiler compiler = PROCESSOR.newXsltCompiler();
                xeSurefire = compiler.compile(new StreamSource(getClass().getResourceAsStream("/surefire-reporter.xsl")));
            }

            boolean failed = false;
            processedFiles= new ArrayList<>(xspecs.size());
            for (final File xspec : xspecs) {
                if (shouldExclude(xspec)) {
                    getLog().warn("Skipping excluded XSpec: " + xspec.getAbsolutePath());
                } else {
                    if (!processXSpec(xspec, xeCompiler, xtReporter, xeSurefire, resolver)) {
                        failed = true;
                    }
                }
            }

            // extract css
            File cssFile = new File(getReportDir(),RESOURCES_TEST_REPORT_CSS);
            cssFile.getParentFile().mkdirs();
            try {
                Source cssSource = resolver.resolve(XSPEC_PREFIX+"xspec/reporter/test-report.css", baseUri);
                BufferedInputStream is = new BufferedInputStream(new URL(cssSource.getSystemId()).openStream());
                BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(cssFile));
                byte[] buffer = new byte[1024];
                int read = is.read(buffer);
                while(read>0) {
                    bos.write(buffer, 0, read);
                    read = is.read(buffer);
                }
            } catch (IOException | TransformerException ex) {
                getLog().error("while extracting CSS: ",ex);
            }

            if (failed) {
                throw new MojoFailureException("Some XSpec tests failed or were missed!");
            }
        } catch (SaxonApiException | TransformerException | MalformedURLException sae) {
            getLog().info("In catch");
            getLog().error("Unable to compile the XSpec Compiler: " + compilerPath,sae);
            throw new MojoExecutionException(sae.getMessage(), sae);
        } finally {
            PROCESS_FILES.addAll(processedFiles);
            // if there is many executions, index file is generated each time, but results are appended...
            generateIndex();
        }
    }
    
    private void generateIndex() {
        File index = new File(reportDir, "index.html");
        try (BufferedWriter fos = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(index), Charset.forName("UTF-8")))) {
            fos.write("<!DOCTYPE HTML PUBLIC \"-//W3C//DTD HTML 4.01 Transitional//EN\" \"http://www.w3.org/TR/html4/loose.dtd\">\n");
            fos.write("<html>");
            fos.write("<head><meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8\">\n");
            fos.write("<link rel=\"stylesheet\" type=\"text/css\" href=\"" + RESOURCES_TEST_REPORT_CSS + "\"/>");
            fos.write("<style>\n\ttable {border:1px solid black; border-collapse:collapse; text-align:center;}\n");
            fos.write("\ttd,th {border:1px solid black; padding:2px;}\n");
            fos.write("\tth, td.zero{background-color:white;}\n");
            fos.write("</style>\n");
            fos.write("<title>XSpec results</title><meta name=\"date\" content=\"");
            fos.write(new SimpleDateFormat("yyyy-MM-dd").format(new Date()));
            fos.write("\"></head>\n");
            fos.write("<body><h1>XSpec results</h1>");
            fos.write("<table>\n");
            fos.write("<colgroup><col/><col class=\"successful\"/><col class=\"pending\"/><col class=\"failed\"/><col class=\"missed\"/><col/></colgroup>\n");
            fos.write("<thead><tr><th>XSpec file</th><th>Passed</th><th>Pending</th><th>Failed</th><th>Missed</th><th>Total</th></tr></thead>\n");
            fos.write("<tbody>");
            String lastRootDir="";
            for(ProcessedFile pf:PROCESS_FILES) {
                String rootDir = pf.getRootSourceDir().toString();
                if(!lastRootDir.equals(rootDir)) {
                    fos.write("<tr><td colspan=\"6\" style=\"text-align:left;\">");
                    fos.write(rootDir);
                    fos.write("</td></tr>\n");
                    lastRootDir = rootDir;
                }
                fos.write("<tr><td><a href=\"");
                fos.write(pf.getReportFile().toUri().toString());
                fos.write("\">"+pf.getRelativeSourcePath()+"</a></td>");
                fos.write("<td");
                if(pf.getPassed()==0) fos.write(" class=\"zero\"");
                fos.write(">"+pf.getPassed()+"</td>");
                fos.write("<td");
                if(pf.getPending()==0) fos.write(" class=\"zero\"");
                fos.write(">"+pf.getPending()+"</td>");
                fos.write("<td");
                if(pf.getFailed()==0) fos.write(" class=\"zero\"");
                fos.write(">"+pf.getFailed()+"</td>");
                fos.write("<td");
                if(pf.getMissed()==0) fos.write(" class=\"zero\"");
                fos.write(">"+pf.getMissed()+"</td>");
                fos.write("<td>"+pf.getTotal()+"</td>");
                fos.write("</tr>\n");
            }
            fos.write("</tbody></table>");
            fos.write("</body></html>");
            fos.flush();
        } catch (IOException ex) {
            getLog().warn("while writing XSpec index file", ex);
        }
    }
    private static final String RESOURCES_TEST_REPORT_CSS = "resources/test-report.css";

    /**
     * Checks whether an XSpec should be excluded from processing
     *
     * The comparison is performed on the filename of the xspec
     *
     * @param xspec The filepath of the XSpec
     *
     * @return true if the XSpec should be excluded, false otherwise
     */
    private boolean shouldExclude(final File xspec) {
        final List<String> excludePatterns = getExcludes();
        if (excludePatterns != null) {
            for (final String excludePattern : excludePatterns) {
                if (xspec.getAbsolutePath().endsWith(excludePattern)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Process an XSpec Test
     *
     * @param xspec The path to the XSpec test file
     * @param compiler A transformer for the XSpec compiler
     * @param reporter A transformer for the XSpec reporter
     *
     * @return true if all tests in XSpec pass, false otherwise
     */
    final boolean processXSpec(final File xspec, final XsltExecutable executable, final XsltTransformer reporter, final XsltExecutable xeSurefire, final URIResolver uriResolver) {
        getLog().info("Processing XSpec: " + xspec.getAbsolutePath());
        
        boolean processedFileAdded = false;

        /* compile the test stylesheet */
        final CompiledXSpec compiledXSpec = compileXSpec(executable, xspec);
        if (compiledXSpec == null) {
            return false;
        } else {
            /* execute the test stylesheet */
            final XSpecResultsHandler resultsHandler = new XSpecResultsHandler(this);
            try {
                final XsltExecutable xeXSpec = xsltCompiler.compile(new StreamSource(compiledXSpec.getCompiledStylesheet()));
                final XsltTransformer xtXSpec = xeXSpec.load();
                xtXSpec.setInitialTemplate(INITIAL_TEMPLATE_NAME);

                getLog().info("Executing XSpec: " + compiledXSpec.getCompiledStylesheet().getName());

                //setup xml report output
                final File xspecXmlResult = getXSpecXmlResultPath(getReportDir(), xspec);
                final Serializer xmlSerializer = PROCESSOR.newSerializer();
                xmlSerializer.setOutputProperty(Serializer.Property.METHOD, "xml");
                xmlSerializer.setOutputProperty(Serializer.Property.INDENT, "yes");
                xmlSerializer.setOutputFile(xspecXmlResult);

                //setup html report output
                final File xspecHtmlResult = getXSpecHtmlResultPath(getReportDir(), xspec);
                final Serializer htmlSerializer = PROCESSOR.newSerializer();
                htmlSerializer.setOutputProperty(Serializer.Property.METHOD, "html");
                htmlSerializer.setOutputProperty(Serializer.Property.INDENT, "yes");
                htmlSerializer.setOutputFile(xspecHtmlResult);
                reporter.setBaseOutputURI(xspecHtmlResult.toURI().toString());
                reporter.setDestination(htmlSerializer);


                // setup surefire report output
                Destination xtSurefire = null;
                if(xeSurefire!=null) {
                    XsltTransformer xt = xeSurefire.load();
                    try {
                        xt.setParameter(new QName("baseDir"), new XdmAtomicValue(project.getBasedir().toURI().toURL().toExternalForm()));
                        xt.setParameter(new QName("outputDir"), new XdmAtomicValue(surefireReportDir.toURI().toURL().toExternalForm()));
                        xt.setParameter(new QName("reportFileName"), new XdmAtomicValue(xspecXmlResult.getName()));
                        xt.setDestination(PROCESSOR.newSerializer(new NullOutputStream()));
                        // setBaseOutputURI not required, surefire-reporter.xsl 
                        // does xsl:result-document with absolute @href
                        xtSurefire = xt;
                    } catch(Exception ex) {
                        getLog().warn("Unable to generate surefire report", ex);
                    }
                } else {
                    xtSurefire = PROCESSOR.newSerializer(new NullOutputStream());
                }
                ProcessedFile pf = new ProcessedFile(testDir, xspec, reportDir, xspecHtmlResult);
                processedFiles.add(pf);
                processedFileAdded = true;
                String relativeCssPath = 
                        (pf.getRelativeCssPath().length()>0 ? pf.getRelativeCssPath()+"/" : "") + RESOURCES_TEST_REPORT_CSS;
                reporter.setParameter(new QName("report-css-uri"), new XdmAtomicValue(relativeCssPath));
                //execute
                final Destination destination = 
                        new TeeDestination(
                                new TeeDestination(
                                        new SAXDestination(resultsHandler), 
                                        new TeeDestination(
                                                xmlSerializer,
                                                xtSurefire)
                                        ), 
                                reporter);
                xtXSpec.setDestination(destination);
                xtXSpec.setBaseOutputURI(xspecXmlResult.toURI().toString());
                Source xspecSource = new StreamSource(xspec);
                xtXSpec.setSource(xspecSource);
                xtXSpec.setURIResolver(uriResolver);
                xtXSpec.transform();

            } catch (final SaxonApiException te) {
                getLog().error(te.getMessage());
                getLog().debug(te);
                if(!processedFileAdded) {
                    ProcessedFile pf = new ProcessedFile(testDir, xspec, reportDir, getXSpecHtmlResultPath(getReportDir(), xspec));
                    processedFiles.add(pf);
                    processedFileAdded = true;
                }
            }
            
            //missed tests come about when the XSLT processor aborts processing the XSpec due to an XSLT error
            final int missed = compiledXSpec.getTests() - resultsHandler.getTests();

            //report results
            final String msg = String.format("%s results [Passed/Pending/Failed/Missed/Total] = [%d/%d/%d/%d/%d]", 
                    xspec.getName(), 
                    resultsHandler.getPassed(), 
		    resultsHandler.getPending(),
                    resultsHandler.getFailed(), 
                    missed, 
                    compiledXSpec.getTests());
            if(processedFiles.size()>0) {
                processedFiles.get(processedFiles.size()-1).setResults(
                        resultsHandler.getPassed(), 
                        resultsHandler.getPending(), 
                        resultsHandler.getFailed(), 
                        missed, 
                        compiledXSpec.getTests());
            }
            if (resultsHandler.getFailed() + missed > 0) {
                getLog().error(msg);
                return false;
            } else {
                getLog().info(msg);
                return true;
            }
        }
    }

    /**
     * Compiles an XSpec using the provided XSLT XSpec compiler
     *
     * @param compiler The XSpec XSLT compiler
     * @param xspec The XSpec test to compile
     *
     * @return Details of the Compiled XSpec or null if the XSpec could not be
     * compiled
     */
    final CompiledXSpec compileXSpec(final XsltExecutable executable, final File xspec) {
        XsltTransformer compiler = executable.load();
        InputStream isXSpec = null;
        try {
            final File compiledXSpec = getCompiledXSpecPath(getReportDir(), xspec);
            getLog().info("Compiling XSpec to XSLT: " + compiledXSpec);

            isXSpec = new FileInputStream(xspec);

            final SAXParser parser = PARSER_FACTORY.newSAXParser();
            final XMLReader reader = parser.getXMLReader();
            final XSpecTestFilter xspecTestFilter = new XSpecTestFilter(reader, xspec.getAbsolutePath(), xsltCompiler.getURIResolver(), this, getLog().isDebugEnabled());

            final InputSource inXSpec = new InputSource(isXSpec);
            inXSpec.setSystemId(xspec.getAbsolutePath());

            compiler.setSource(new SAXSource(xspecTestFilter, inXSpec));

            final Serializer serializer = PROCESSOR.newSerializer();
            serializer.setOutputFile(compiledXSpec);
            compiler.setDestination(serializer);

            compiler.transform();

            return new CompiledXSpec(xspecTestFilter.getTests(), xspecTestFilter.getPendingTests(), compiledXSpec);

        } catch (final SaxonApiException sae) {
            getLog().error(sae.getMessage());
            getLog().debug(sae);
        } catch (final ParserConfigurationException | FileNotFoundException pce) {
            getLog().error(pce);
        } catch (SAXException saxe) {
            getLog().error(saxe.getMessage());
            getLog().debug(saxe);
        } finally {
            if (isXSpec != null) {
                try {
                    isXSpec.close();
                } catch (final IOException ioe) {
                    getLog().warn(ioe);
                }
            }
        }

        return null;
    }

    /**
     * Get location for Compiled XSpecs
     *
     * @param xspecReportDir The directory to place XSpec reports in
     * @param xspec The XSpec that will be compiled eventually
     *
     * @return A filepath to place the compiled XSpec in
     */
    final File getCompiledXSpecPath(final File xspecReportDir, final File xspec) {
        if (!xspecReportDir.exists()) {
            xspecReportDir.mkdirs();
        }
        Path relativeSource = testDir.toPath().relativize(xspec.toPath());
//        Path relativeSource = xspec.getParentFile().toPath().relativize(testDir.toPath());
        File executionReportDir = (
                execution!=null && execution.getExecutionId()!=null && !"default".equals(execution.getExecutionId()) ? 
                new File(xspecReportDir,execution.getExecutionId()) :
                xspecReportDir);
        executionReportDir.mkdirs();
        File outputDir = executionReportDir.toPath().resolve(relativeSource).toFile();
        final File fCompiledDir = new File(outputDir, "xslt");
        if (!fCompiledDir.exists()) {
            fCompiledDir.mkdirs();
        }
        return new File(fCompiledDir, xspec.getName() + ".xslt");
    }

    /**
     * Get location for XSpec test report (XML Format)
     *
     * @param xspecReportDir The directory to place XSpec reports in
     * @param xspec The XSpec that will be compiled eventually
     *
     * @return A filepath for the report
     */
    final File getXSpecXmlResultPath(final File xspecReportDir, final File xspec) {
        return getXSpecResultPath(xspecReportDir, xspec, "xml");
    }

    /**
     * Get location for XSpec test report (HTML Format)
     *
     * @param xspecReportDir The directory to place XSpec reports in
     * @param xspec The XSpec that will be compiled eventually
     *
     * @return A filepath for the report
     */
    final File getXSpecHtmlResultPath(final File xspecReportDir, final File xspec) {
        return getXSpecResultPath(xspecReportDir, xspec, "html");
    }

    /**
     * Get location for XSpec test report
     *
     * @param xspecReportDir The directory to place XSpec reports in
     * @param xspec The XSpec that will be compiled eventually
     * @param extension Filename extension for the report (excluding the '.'
     *
     * @return A filepath for the report
     */
    final File getXSpecResultPath(final File xspecReportDir, final File xspec, final String extension) {
        if (!xspecReportDir.exists()) {
            xspecReportDir.mkdirs();
        }
        Path relativeSource = testDir.toPath().relativize(xspec.toPath());
        getLog().debug("executionId="+execution.getExecutionId());
        getLog().debug("relativeSource="+relativeSource.toString());
        File executionReportDir = (
                execution!=null && execution.getExecutionId()!=null && !"default".equals(execution.getExecutionId()) ? 
                new File(xspecReportDir,execution.getExecutionId()) :
                xspecReportDir);
        executionReportDir.mkdirs();
        getLog().debug("executionReportDir="+executionReportDir.getAbsolutePath());
        File outputDir = executionReportDir.toPath().resolve(relativeSource).toFile();
        getLog().debug("outputDir="+outputDir.getAbsolutePath());
        return new File(outputDir, xspec.getName().replace(".xspec", "") + "." + extension);
    }

    /**
     * Recursively find any files whoose name ends '.xspec' under the directory
     * xspecTestDir
     *
     * @param xspecTestDir The directory to search for XSpec files
     *
     * @return List of XSpec files
     */
    private List<File> findAllXSpecs(final File xspecTestDir) {

        final List<File> specs = new ArrayList<>();

        if (xspecTestDir.exists()) {
            final File[] specFiles = xspecTestDir.listFiles(new FileFilter() {
                @Override
                public boolean accept(final File file) {
                    return file.isFile() && file.getName().endsWith(".xspec");
                }
            });
            specs.addAll(Arrays.asList(specFiles));

            for (final File subDir : xspecTestDir.listFiles(new FileFilter() {
                @Override
                public boolean accept(final File file) {
                    return file.isDirectory();
                }
            })) {
                specs.addAll(findAllXSpecs(subDir));
            }
        }

        return specs;
    }

    protected boolean isSkipTests() {
        return skipTests;
    }

    protected String getXspecCompiler() {
        return xspecCompiler;
    }

    protected String getXspecReporter() {
        return xspecReporter;
    }

    protected File getReportDir() {
        return reportDir;
    }

    protected File getTestDir() {
        return testDir;
    }

    protected List<String> getExcludes() {
        return excludes;
    }

    private URIResolver buildUriResolver(final URIResolver saxonUriResolver) throws DependencyResolutionRequiredException, IOException, XMLStreamException, MojoFailureException {
        String thisJar = null;
        String marker = createMarker();
        getLog().debug("marker="+marker);
        for(String s:getClassPathElements()) {
//            getLog().debug("\t"+s);
            if(s.contains(marker)) {
                thisJar = s;
                break;
            }
        }
        if(thisJar==null) {
            throw new MojoFailureException("Unable to locate plugin jar file from classpath-");
        }
        String jarUri = makeJarUri(thisJar);
        File tmpCatalog = File.createTempFile("tmp", "-catalog.xml");
        try (OutputStreamWriter osw = new OutputStreamWriter(new FileOutputStream(tmpCatalog), Charset.forName("UTF-8"))) {
            XMLStreamWriter xmlWriter = XMLOutputFactory.newFactory().createXMLStreamWriter(osw);
            xmlWriter.writeStartDocument("UTF-8", "1.0");
            xmlWriter.writeStartElement("catalog");
            xmlWriter.setDefaultNamespace(CATALOG_NS);
            xmlWriter.writeNamespace("", CATALOG_NS);
            xmlWriter.writeStartElement("rewriteURI");
            xmlWriter.writeAttribute("uriStartString", XSPEC_PREFIX);
            xmlWriter.writeAttribute("rewritePrefix", jarUri);
            xmlWriter.writeEndElement();
            xmlWriter.writeStartElement("rewriteSystem");
            xmlWriter.writeAttribute("uriStartString", XSPEC_PREFIX);
            xmlWriter.writeAttribute("rewritePrefix", jarUri);
            xmlWriter.writeEndElement();
            if(catalogFile!=null) {
                xmlWriter.writeStartElement("nextCatalog");
                xmlWriter.writeAttribute("catalog", catalogFile.toURI().toURL().toExternalForm());
                xmlWriter.writeEndElement();
            }
            xmlWriter.writeEndElement();
            xmlWriter.writeEndDocument();
            osw.flush();
//            System.setProperty("xml.catalog.files", tmpCatalog.getAbsolutePath());
        }
        if(!keepGeneratedCatalog) tmpCatalog.deleteOnExit();
        else getLog().info("keeping generated catalog: "+tmpCatalog.toURI().toURL().toExternalForm());
        return new Resolver(saxonUriResolver, tmpCatalog, getLog());
    }
    
    private String makeJarUri(String jarFile) throws MalformedURLException {
        getLog().debug(String.format("makeJarUri(%s)", jarFile));
        return "jar:" + jarFile +"!/";
    }
    
    private List<String> getClassPathElements() throws MojoFailureException {
        ClassLoader cl = getClass().getClassLoader();
        if(cl instanceof URLClassLoader) {
            URLClassLoader ucl = (URLClassLoader)cl;
            List<String> ret = new ArrayList(ucl.getURLs().length);
            for(URL u:ucl.getURLs()) {
                ret.add(u.toExternalForm());
            }
            return ret;
        } else {
            throw new MojoFailureException("classloader is not a URL classloader : "+cl.getClass().getName());
        }
    }
    
    private String createMarker() throws IOException {
        Properties props = new Properties();
        props.load(getClass().getResourceAsStream("/xspec-maven-plugin.properties"));
        return String.format("%s-%s", props.getProperty("plugin.artifactId"), props.getProperty("plugin.version"));
    }

    static final String XSPEC_MOJO_PFX = "[xspec-mojo] ";
        
    private static Configuration getSaxonConfiguration() {
        Configuration ret = Configuration.newConfiguration();
        ret.setConfigurationProperty("http://saxon.sf.net/feature/allow-external-functions", Boolean.TRUE);
        return ret;
    }
    
}
