package org.kurento.ktool.maven;

import java.io.File;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

/**
 * Parses Kurento Media Elements Definition (*.kmd.json) and transforms them
 * into Java source files.
 *
 * @author micael.gallego@gmail.com
 */
@Mojo(defaultPhase = LifecyclePhase.GENERATE_SOURCES, name = "generate-js-media-api", requiresDependencyResolution = ResolutionScope.COMPILE, requiresProject = true)
public class GenerateJsMediaApiMojo extends AbstractGenerateMediaApiMojo {

	/**
	 * The directory where the Kurento Media Element Definition files (
	 * {@code *.kmd.json}) are located.
	 */
	@Parameter(defaultValue = "${basedir}/kmd")
	private File sourceDirectory;

	/**
	 * Specify output directory where the JavaScript files are generated.
	 */
	@Parameter(readonly = true, defaultValue = "${basedir}/lib")
	protected File generatedSourceOutputFolder;

	@Parameter(defaultValue = "${basedir}/lib/kmd", readonly = true)
	private File kmdOutputFolder;

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

		executeKurentoMavenPlugin("js", sourceDirectory,
				generatedSourceOutputFolder, kmdOutputFolder);
	}
}