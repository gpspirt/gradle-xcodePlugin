package org.openbakery

import groovy.io.FileType
import org.gradle.api.logging.LogLevel
import org.gradle.api.tasks.TaskAction
import org.gradle.internal.logging.progress.ProgressLogger
import org.gradle.internal.logging.progress.ProgressLoggerFactory
import org.gradle.internal.logging.text.StyledTextOutput
import org.gradle.internal.logging.text.StyledTextOutputFactory
import org.gradle.util.ConfigureUtil
import org.openbakery.codesign.Codesign
import org.openbakery.output.TestBuildOutputAppender
import org.openbakery.test.TestResultParser
import org.openbakery.xcode.Destination
import org.openbakery.xcode.Type
import org.openbakery.xcode.Xcodebuild
import org.openbakery.xcode.XcodebuildParameters

/**
 * User: rene
 * Date: 25/10/16
 */
class XcodeTestRunTask extends AbstractXcodeBuildTask {

	private List<Destination> destinationsCache

	Object bundleDirectory
	TestResultParser testResultParser = null
	File outputDirectory = null

	Codesign codesign = null

	XcodeTestRunTask() {
		super()
		dependsOn(
			XcodePlugin.SIMULATORS_KILL_TASK_NAME
		)
		this.description = "Create a build for test of the Xcode project"
	}


	TestBuildOutputAppender createOutputAppender(List<Destination> destinations) {
		String name = getClass().getName()
		StyledTextOutput output = getServices().get(StyledTextOutputFactory.class).create(getClass(), LogLevel.LIFECYCLE);
		ProgressLoggerFactory progressLoggerFactory = getServices().get(ProgressLoggerFactory.class);
		ProgressLogger progressLogger = progressLoggerFactory.newOperation(getClass()).start(name, name);
		return new TestBuildOutputAppender(progressLogger, output, destinations)
	}

	@TaskAction
	def testRun() {
		parameters = project.xcodebuild.xcodebuildParameters.merge(parameters)
		parameters.xctestrun = getXcruntestFiles()

		File testLogsDirectory = new File(parameters.derivedDataPath, "Logs/Test")
		testLogsDirectory.deleteDir()

		outputDirectory = new File(project.getBuildDir(), "test");
		if (!outputDirectory.exists()) {
			outputDirectory.mkdirs()
		}
		commandRunner.setOutputFile(new File(outputDirectory, "xcodebuild-output.txt"));


		if (runOnDevice()) {
			logger.lifecycle("Perform codesign")
			Codesign codesign = getCodesign()

			parameters.xctestrun.each() {

				String appBundle = getBundleFromFile(it, "TestHostPath")
				File appBundleFile = new File(it.parentFile, appBundle)
				codesign.sign(appBundleFile)

				String testBundle = getBundleFromFile(it, "TestBundlePath")
				File testBundleFile = new File(appBundleFile, testBundle)
				codesign.sign(testBundleFile)
			}

		}

		def destinations = getDestinations()
		try {
			Xcodebuild xcodebuild = new Xcodebuild(project.projectDir, commandRunner, xcode, parameters, destinations)
			xcodebuild.executeTestWithoutBuilding(createOutputAppender(destinations), project.xcodebuild.environment)
		} catch (CommandRunnerException ex) {
			throw new Exception("Error attempting to run the unit tests!", ex);
		} finally {
			testResultParser = new TestResultParser(testLogsDirectory, destinations)
			testResultParser.parseAndStore(outputDirectory)
			int numberSuccess = testResultParser.numberSuccess()
			int numberErrors = testResultParser.numberErrors()
			if (numberErrors == 0) {
				logger.lifecycle("All " + numberSuccess + " tests were successful");
			} else {
				logger.lifecycle(numberSuccess + " tests were successful, and " + numberErrors + " failed");
			}
			if (numberErrors != 0) {
				throw new Exception("Not all unit tests are successful!")
			}

		}
	}

	String getBundleFromFile(File file, String key) {
		String bundle = plistHelper.getValueFromPlist(file, "Tests:" + key)
		if (bundle.startsWith("__TESTROOT__/")) {
			bundle = bundle - "__TESTROOT__/"
		}
		if (bundle.startsWith("__TESTHOST__/")) {
			bundle = bundle - "__TESTHOST__/"
		}
		return bundle
	}


	void setBundleDirectory(Object bundleDirectory) {
		this.bundleDirectory = bundleDirectory
	}

	File getBundleDirectory() {
		if (bundleDirectory instanceof File) {
			return bundleDirectory
		}
		if (bundleDirectory != null) {
			return project.file(bundleDirectory)
		}
		return new File(".")
	}


	def getXcruntestFiles() {
		List<File> result = []
		getBundleDirectory().eachFileRecurse(FileType.FILES) {
		    if(it.name.endsWith('.xctestrun')) {
					result << it
		    }
		}
		return result
	}

	boolean runOnDevice() {
		if (parameters.type != Type.OSX) {
			// os x does not have a simulator
			return !parameters.simulator
		}
		return true
	}

	Codesign getCodesign() {
		if (runOnDevice()) {
			if (codesign == null) {
				codesign = new Codesign(xcode, project.xcodebuild.signing.identity, project.xcodebuild.signing.keychainPathInternal, project.xcodebuild.signing.entitlementsFile, project.xcodebuild.signing.mobileProvisionFile, project.xcodebuild.type,  commandRunner, plistHelper)
			}
		}
		return codesign
	}
}
