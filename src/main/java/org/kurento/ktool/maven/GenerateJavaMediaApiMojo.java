package org.kurento.ktool.maven;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.compiler.util.scan.InclusionScanException;
import org.codehaus.plexus.compiler.util.scan.SimpleSourceInclusionScanner;
import org.codehaus.plexus.compiler.util.scan.SourceInclusionScanner;
import org.codehaus.plexus.compiler.util.scan.mapping.SourceMapping;
import org.codehaus.plexus.compiler.util.scan.mapping.SuffixMapping;
import org.sonatype.plexus.build.incremental.BuildContext;

import com.google.gson.JsonObject;
import com.kurento.ktool.rom.processor.codegen.Error;
import com.kurento.ktool.rom.processor.codegen.KurentoRomProcessor;
import com.kurento.ktool.rom.processor.codegen.KurentoRomProcessorException;
import com.kurento.ktool.rom.processor.codegen.PathUtils;
import com.kurento.ktool.rom.processor.codegen.Result;

/**
 * Parses Kurento Media Elements Definition (*.kmd.json) and transforms them
 * into Java source files.
 *
 * @author micael.gallego@gmail.com
 */
@Mojo(defaultPhase = LifecyclePhase.GENERATE_SOURCES, name = "generate-java-media-api", requiresDependencyResolution = ResolutionScope.COMPILE, requiresProject = true)
public class GenerateJavaMediaApiMojo extends AbstractMojo {

	private Log log;

	/**
	 * specify kurento media element definition file encoding; e.g., euc-jp
	 */
	@Parameter(property = "project.build.sourceEncoding")
	protected String encoding;

	/**
	 * Treat warnings as errors.
	 */
	@Parameter(property = "kcg.treatWarningsAsErrors", defaultValue = "false")
	protected boolean treatWarningsAsErrors;

	/**
	 * A list of grammar options to explicitly specify to the tool. These
	 * options are passed to the tool using the
	 * <code>-D&lt;option&gt;=&lt;value&gt;</code> syntax.
	 */
	@Parameter
	protected Map<String, String> options;

	/**
	 * A list of additional command line arguments to pass to the KCG tool.
	 */
	@Parameter
	protected List<String> arguments;

	/*
	 * ----------------------------------------------------------------------
	 * The following are Maven specific parameters, rather than specific options
	 * that the Kurento Maven Plugin can use.
	 */

	/**
	 * Provides an explicit list of all the Kurento Media Element Definitions
	 * (kmd) that should be included in the generate phase of the plugin.
	 * <p/>
	 * A set of Ant-like inclusion patterns used to select files from the source
	 * directory for processing. By default, the pattern
	 * <code>**&#47;*.kmd.json</code> is used to select kmd files.
	 */
	@Parameter
	protected Set<String> includes = new HashSet<String>();
	/**
	 * A set of Ant-like exclusion patterns used to prevent certain files from
	 * being processed. By default, this set is empty such that no files are
	 * excluded.
	 */
	@Parameter
	protected Set<String> excludes = new HashSet<String>();

	/**
	 * The current Maven project.
	 */
	@Parameter(property = "project", required = true, readonly = true)
	protected MavenProject project;

	/**
	 * The directory where the Kurento Media Element Definition files (
	 * {@code *.kmd.json}) are located.
	 */
	@Parameter(defaultValue = "${basedir}/src/main/kmd")
	private File sourceDirectory;

	/**
	 * Specify output directory where the Java files are generated.
	 */
	@Parameter(readonly = true, defaultValue = "${project.build.directory}/generated-sources/kmd")
	private File outputDirectory;

	@Parameter(defaultValue = "${project.build.outputDirectory}/META-INF/kurento", readonly = true)
	private File kurentoOutputFolder;

	@Component
	private BuildContext buildContext;

	@Parameter
	private List<String> generateCodeForModules = Collections.emptyList();

	public File getSourceDirectory() {
		return sourceDirectory;
	}

	void addSourceRoot(File outputDir) {
		project.addCompileSourceRoot(outputDir.getPath());
	}

	/**
	 * The main entry point for this Mojo, it is responsible for converting
	 * Kurento Media Element Descriptions into the Java code used by
	 * kmf-media-api clients.
	 *
	 * @exception MojoExecutionException
	 *                if a configuration or definition error causes the code
	 *                generation process to fail
	 * @exception MojoFailureException
	 *                if an instance of the Kurento Maven Plugin cannot be
	 *                created
	 */
	@Override
	public void execute() throws MojoExecutionException, MojoFailureException {

		log = getLog();

		showDebugInfo();

		Set<File> kmdFiles = loadKmdFiles();

		try {

			KurentoDependencyManager manager = new KurentoDependencyManager(log);

			manager.loadDependencies(project);

			KurentoRomProcessor krp = new KurentoRomProcessor();
			addKmdFiles(krp, kmdFiles, manager);
			krp.loadModelsFromKmdFiles();

			if (krp.hasToGenerateCode()) {

				JsonObject config = createConfig(manager);
				krp.setDeleteGenDir(true);
				krp.setVerbose(false);
				krp.setInternalTemplates("java");
				krp.setCodeGenDir(outputDirectory.toPath());
				krp.setConfig(config);
				krp.setListGeneratedFiles(false);

				prepareOutputDirectories();

				Result result = krp.generateCode();

				if (result.isSuccess()) {
					getLog().info("Generation success");
				} else {
					getLog().error("Generation failed");

					getLog().error("Errors:");
					for (Error error : result.getErrors()) {
						getLog().error(error.toString());
					}

					throw new MojoExecutionException(
							"Kurento Rom Processor found errors: "
									+ result.getErrors());
				}

				addSourceRoot(outputDirectory);
			}

		} catch (MojoExecutionException e) {
			throw e;
		} catch (Exception e) {
			log.error(
					"Exception "
							+ e.getClass().getName()
							+ ":"
							+ e.getMessage()
							+ " in code generation from kmd files. See exception report for details",
					e);
			throw new MojoFailureException(
					"Exception in code generation from kmd files. See exception report for details",
					e);
		}

		copyKmdFiles(kmdFiles);
	}

	private void addKmdFiles(KurentoRomProcessor krp, Set<File> kmdFiles,
			KurentoDependencyManager manager) {

		log.info("Preparing code generation tool:");

		for (File kmdFile : kmdFiles) {
			getLog().info("  Adding kmd file to generate code: " + kmdFile);
			krp.addKmdFileToGen(kmdFile.toPath());
		}

		for (String moduleToGenerateCode : this.generateCodeForModules) {
			if (manager.getDependency(moduleToGenerateCode) == null) {
				throw new KurentoRomProcessorException(
						"The module to generate code '" + moduleToGenerateCode
								+ "' doesn't exist in dependencies");
			}
		}

		for (KurentoArtifact dependency : manager.getDependencies()) {

			Path kmdFile = dependency.getKmdFile();
			if (!this.generateCodeForModules.contains(dependency.getName())) {
				getLog().info("  Adding dependency kmd file: " + kmdFile);
				krp.addDependencyKmdFile(kmdFile);
			} else {
				getLog().info("  Adding kmd file to generate code: " + kmdFile);
				krp.addDependencyKmdFileToGen(kmdFile);
			}
		}
	}

	private JsonObject createConfig(KurentoDependencyManager manager) {

		JsonObject config = new JsonObject();
		config.addProperty("expandMethodsWithOpsParams", true);
		return config;
	}

	private void copyKmdFiles(Set<File> kmdFiles) throws MojoFailureException {

		try {
			Path outputPath = kurentoOutputFolder.toPath();

			if (Files.exists(outputPath)) {
				PathUtils.deleteRecursive(outputPath);
			}

			if (!kmdFiles.isEmpty()) {
				Files.createDirectories(outputPath);
			}

			for (File kmdFile : kmdFiles) {

				Path kmdPath = kmdFile.toPath();
				String kmdFileName = kmdPath.getFileName().toString();
				Path newFile = outputPath.resolve(kmdFileName);
				Files.copy(kmdPath, newFile,
						StandardCopyOption.REPLACE_EXISTING);
				buildContext.refresh(newFile.toFile());
			}
		} catch (IOException e) {
			throw new MojoFailureException("Exception copying kmd files", e);
		}
	}

	private void prepareOutputDirectories() {
		if (!outputDirectory.exists()) {
			outputDirectory.mkdirs();
		}

		if (!kurentoOutputFolder.exists()) {
			kurentoOutputFolder.mkdirs();
		}
	}

	private void showDebugInfo() {

		Log log = getLog();

		if (log.isDebugEnabled()) {
			for (String e : excludes) {
				log.debug("KMP: Exclude: " + e);
			}

			for (String e : includes) {
				log.debug("KMP: Include: " + e);
			}

			log.debug("KMP: Output: " + outputDirectory);
		}
	}

	private Set<File> loadKmdFiles() throws MojoFailureException {

		Set<File> kmdFiles = Collections.emptySet();
		if (!sourceDirectory.isDirectory()) {
			getLog().info(
					"The folder for Kurento Media Element Definition files (*.kmd.json) is \""
							+ sourceDirectory.getAbsolutePath()
							+ "\", but it doesn't exist");
		} else {

			getLog().info(
					"Searching for kmd files in "
							+ sourceDirectory.getAbsolutePath());
			try {
				kmdFiles = loadKmdFiles(sourceDirectory);
				for (File kmdFile : kmdFiles) {
					getLog().info(
							"  Found kmd file to generate code: " + kmdFile);
				}

			} catch (InclusionScanException e) {
				throw new MojoFailureException("Exception loading kmd files", e);
			}
		}

		return kmdFiles;
	}

	/**
	 *
	 * @param sourceDirectory
	 * @exception InclusionScanException
	 */
	private Set<File> loadKmdFiles(File sourceDirectory)
			throws InclusionScanException {

		// Which files under the source set should we be looking for as kmd
		// files
		SourceMapping mapping = new SuffixMapping("kmd.json",
				Collections.<String> emptySet());

		// What are the sets of includes (defaulted or otherwise).
		Set<String> includes = getIncludesPatterns();

		SourceInclusionScanner scan = new SimpleSourceInclusionScanner(
				includes, excludes);

		scan.addSourceMapping(mapping);

		Set<File> kmdFiles = scan.getIncludedSources(sourceDirectory, null);

		if (kmdFiles.isEmpty()) {
			getLog().info("No kmd files to process in the project");
			return Collections.emptySet();
		} else {
			return kmdFiles;
		}
	}

	public Set<String> getIncludesPatterns() {
		if (includes == null || includes.isEmpty()) {
			return Collections.singleton("**/*.kmd.json");
		}
		return includes;
	}

}