package com.jvoegele.gradle.plugins.android

import org.gradle.api.logging.LogLevel;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPlugin

import com.jvoegele.gradle.tasks.android.AndroidPackageTask;
import com.jvoegele.gradle.tasks.android.ProGuard
import com.jvoegele.gradle.tasks.android.ProcessAndroidResources

/**
 * Gradle plugin that extends the Java plugin for Android development.
 *
 * @author Jason Voegele (jason@jvoegele.com)
 */
class AndroidPlugin implements Plugin<Project> {
  private static final ANDROID_PROCESS_RESOURCES_TASK_NAME = "androidProcessResources"
  private static final PROGUARD_TASK_NAME = "proguard"
  private static final ANDROID_PACKAGE_TASK_NAME = "androidPackage"
  private static final ANDROID_INSTALL_TASK_NAME = "androidInstall"
  private static final ANDROID_UNINSTALL_TASK_NAME = "androidUninstall"

  private static final PROPERTIES_FILES = ['local', 'build', 'default']
  private static final ANDROID_JARS = ['anttasks', 'sdklib', 'androidprefs', 'apkbuilder', 'jarutils']

  private AndroidPluginConvention androidConvention
  private sdkDir
  private toolsDir

  private Project project
  private logger

  private androidProcessResourcesTask, proguardTask, androidPackageTask, 
  androidInstallTask, androidUninstallTask

  boolean verbose = false

  @Override
  public void apply(Project project) {
    project.plugins.apply(JavaPlugin.class)

    this.project = project
    this.logger = project.logger

    androidConvention = new AndroidPluginConvention(project)
    project.convention.plugins.android = androidConvention

    androidSetup()
    defineTasks()
    configureCompile()
  }

  private void androidSetup() {
    def ant = project.ant

    PROPERTIES_FILES.each { ant.property(file: "${it}.properties") }
    sdkDir = ant['sdk.dir']
    toolsDir = new File(sdkDir, "tools")

    ant.path(id: 'android.antlibs') {
      ANDROID_JARS.each { pathelement(path: "${sdkDir}/tools/lib/${it}.jar") }
    }

    ant.condition('property': "exe", value: ".exe", 'else': "") { os(family: "windows") }
    ant.property(name: "adb", location: new File(toolsDir, "adb${ant['exe']}"))
    ant.property(name: "zipalign", location: new File(toolsDir, "zipalign${ant['exe']}"))
    ant.property(name: 'adb.device.arg', value: '')

    def outDir = project.buildDir.absolutePath
    ant.property(name: "resource.package.file.name", value: "${project.name}.ap_")
	/* Use Gradle standard properties instead, like archivePath.
    ant.property(name: "out.debug.unaligned.package", location: "${outDir}/${project.name}-debug-unaligned.apk")
    ant.property(name: "out.debug.package", location: "${outDir}/${project.name}-debug.apk")
    ant.property(name: "out.unsigned.package", location: "${outDir}/${project.name}-unsigned.apk")
    ant.property(name: "out.unaligned.package", location: "${outDir}/${project.name}-unaligned.apk")
    ant.property(name: "out.release.package", location: "${outDir}/${project.name}-release.apk")
    */

    ant.taskdef(name: 'setup', classname: 'com.android.ant.SetupTask', classpathref: 'android.antlibs')

    // The following properties are put in place by the setup task:
    // android.jar, android.aidl, aapt, aidl, and dx
    ant.setup('import': false)

    ant.taskdef(name: "xpath", classname: "com.android.ant.XPathTask", classpathref: "android.antlibs")
    ant.taskdef(name: "aaptexec", classname: "com.android.ant.AaptExecLoopTask", classpathref: "android.antlibs")
    ant.taskdef(name: "apkbuilder", classname: "com.android.ant.ApkBuilderTask", classpathref: "android.antlibs")

    ant.xpath(input: androidConvention.androidManifest, expression: "/manifest/@package", output: "manifest.package")
    ant.xpath(input: androidConvention.androidManifest, expression: "/manifest/application/@android:hasCode",
              output: "manifest.hasCode", 'default': "true")
  }

  private void defineTasks() {
    defineAndroidProcessResourcesTask()
    defineProguardTask()
    defineAndroidPackageTask()
    defineAndroidInstallTask()
    defineAndroidUninstallTask()
    defineTaskDependencies()
    configureTaskLogging()
  }

  private void defineAndroidProcessResourcesTask() {
    androidProcessResourcesTask = project.tasks.add(ANDROID_PROCESS_RESOURCES_TASK_NAME, ProcessAndroidResources.class)
    androidProcessResourcesTask.description = "Generate R.java source file from Android resource XML files"
  }

  private void defineProguardTask() {
    proguardTask = project.tasks.add(PROGUARD_TASK_NAME, ProGuard.class)
    proguardTask.description = "Process classes and JARs with ProGuard"
  }

  private void defineAndroidPackageTask() {
    androidPackageTask = project.tasks.add(ANDROID_PACKAGE_TASK_NAME, AndroidPackageTask.class)
    androidPackageTask.description = "Creates the Android application apk package, optionally signed, zipaligned"
  }

  private void defineAndroidInstallTask() {
    androidInstallTask = project.task(ANDROID_INSTALL_TASK_NAME) << {
      logger.info("Installing ${ant['out.debug.package']} onto default emulator or device...")
      ant.exec(executable: ant['adb'], failonerror: true) {
        arg(line: ant['adb.device.arg'])
        arg(value: 'install')
        arg(value: '-r')
        arg(path: ant['out.debug.package'])
      }
    }
    androidInstallTask.description =
        "Installs the debug package onto a running emulator or device"
  }

  private void defineAndroidUninstallTask() {
    androidUninstallTask = project.task(ANDROID_UNINSTALL_TASK_NAME) << {
      String manifestPackage = null
      try {
        manifestPackage = ant['manifest.package']
      } catch (Exception ignoreBecauseWeCheckForNullLaterAnywayAfterAll) {}
      if (!manifestPackage) {
        logger.error("Unable to uninstall, manifest.package property is not defined.")
      }
      else {
        logger.info("Uninstalling ${ant['manifest.package']} from the default emulator or device...")
        ant.exec(executable: ant['adb'], failonerror: true) {
          arg(line: ant['adb.device.arg'])
          arg(value: "uninstall")
          arg(value: ant['manifest.package'])
        }
      }
    }
    androidUninstallTask.description =
        "Uninstalls the application from a running emulator or device"
  }

  private void defineTaskDependencies() {
    project.tasks.compileJava.dependsOn(androidProcessResourcesTask)
    proguardTask.dependsOn(project.tasks.jar)
    androidPackageTask.dependsOn(project.tasks.jar)
    project.tasks.assemble.dependsOn(ANDROID_PACKAGE_TASK_NAME)
    androidInstallTask.dependsOn(project.tasks.assemble)
  }

  private void configureTaskLogging() {
    androidProcessResourcesTask.logging.captureStandardOutput(LogLevel.INFO)
    proguardTask.logging.captureStandardOutput(LogLevel.INFO)
    androidPackageTask.logging.captureStandardOutput(LogLevel.INFO)
    androidInstallTask.logging.captureStandardOutput(LogLevel.INFO)
    androidUninstallTask.logging.captureStandardOutput(LogLevel.INFO)
  }

  private void configureCompile() {
    def mainSource = project.tasks.compileJava.source
    project.tasks.compileJava.source = [androidConvention.genDir, mainSource]
    project.sourceSets.main.compileClasspath +=
        project.files(project.ant.references['android.target.classpath'].list())
    project.compileJava.options.bootClasspath = project.ant.references['android.target.classpath']
  }

}
