import scala.collection.mutable.ListBuffer
import scala.collection.JavaConversions._
import scala.collection.mutable.{Map => MMap}
import scala.collection.mutable.{Set => MSet}
import scala.collection.immutable.HashMap
import scala.collection.immutable.TreeSet
import scala.collection.mutable.{HashMap => MHashMap}
import scala.collection.mutable.{HashSet => MHashSet}

import proguard.{Configuration=>ProGuardConfiguration, ProGuard, ConfigurationParser}

import sbt._
import Keys._
import AndroidKeys._
import AndroidHelpers._

import java.io.{ File => JFile }

import com.android.dx.io.DexBuffer
import com.android.dx.merge.DexMerger
import com.android.dx.merge.CollisionPolicy

import org.objectweb.asm.depend.DependencyLister

object AndroidFastInstall {
  
  var incrementAppOnly = false
  
  private def devInstallTask(emulator: Boolean) = (dbPath, packageApkPath, streams) map { (dp, p, s) =>
	adbTask(dp.absolutePath, emulator, s, "install", "-r ", p.absolutePath)
  }

  private def devUninstallTask(emulator: Boolean) = (dbPath, manifestPackage, streams) map { (dp, m, s) =>
    adbTask(dp.absolutePath, emulator, s, "uninstall", m)
  }

  private def aaptPackageTask: Project.Initialize[Task[File]] =
  (aaptPath, manifestPath, mainResPath, mainAssetsPath, jarPath, resourcesApkPath, extractApkLibDependencies, streams) map {
    (apPath, manPath, rPath, assetPath, jPath, resApkPath, apklibs, s) =>

    val libraryResPathArgs = for (
      lib <- apklibs;
      d <- lib.resDir.toSeq;
      arg <- Seq("-S", d.absolutePath)
    ) yield arg

    val aapt = Seq(apPath.absolutePath, "package", "--auto-add-overlay", "-f",
        "-M", manPath.head.absolutePath,
        "-S", rPath.absolutePath,
        "-A", assetPath.absolutePath,
        "-I", jPath.absolutePath,
        "-F", resApkPath.absolutePath) ++
        libraryResPathArgs
		
    s.log.debug("packaging: "+aapt.mkString(" "))
    if (aapt.run(false).exitValue != 0) sys.error("error packaging resources")
    resApkPath
  }
  
  /**
   * Google Summer of Code
   *
   * Converts java multi map to scala multi map
   */
  def asMutableScalaMultiMap(jimap: java.util.Map[java.lang.String, java.util.Set[java.lang.String]]): MMap[String, MSet[String]] = {
	MMap(
		jimap.map(kv => (kv._1, MSet(kv._2.toList: _*))).toList: _*
	)
  }
  
  /**
   * Google Summer of Code
   *
   * Checks if the first dependency multi map is a subset of the second
   */
  def isSubset(cDeps: MMap[String, MSet[String]], jDeps: MMap[String, MSet[String]]): Boolean = {
	for (cKey <- cDeps.keys) {
		if (!jDeps.keys.contains(cKey)) {
			return false
		}
	}

	for (cKey <- cDeps.keys) {
		val cSet = cDeps(cKey)
		val jSet = jDeps(cKey)
		for (cValue <- cSet) {
			if (!jSet.contains(cValue)) {
				return false
			}
		}
	}

	return true
  }
  
  /**
   * Google Summer of Code
   *
   * Merges two TreeSet composite HashMap
   */
  def depsUnion(deps1: MMap[String, MSet[String]], deps2: MMap[String, MSet[String]]): MMap[String, MSet[String]] = {
	for (key <- deps2.keys) {
		var set1 = deps1.getOrElse(key, new MHashSet[String]())
		var set2 = deps2.getOrElse(key, new MHashSet[String]())
		set1 ++= set2
		deps1 += (key -> set1)
	}
	
	return deps1
  }
  
  /**
   * Goole Summer of Code
   *
   * Extracts all the class and method dependencies from a dependency map as string.
   */
  def getProGuardKeepArgs(allDeps: MMap[String, MSet[String]]): String = {
	var sb = new StringBuilder()
	for ((clazz, methods) <- allDeps) {
		sb.append("-keep class " + clazz + " {\n")
		
		for (method <- methods) {
			sb.append(" *** " + method + "(...);\n")
		}
		
		sb.append("}\n\n")
	}
	
	sb.toString
  }
  
  /**
   * Google Summer of Code
   *
   * Checks whether the input files are up to date in regard to the output file.
   */
  def isUpToDate(inputs: Seq[JFile], output: JFile): Boolean = {
	val upToDate = output.exists && inputs.forall(input =>
	  input.isDirectory match {
		case true =>
		  (input ** "*").get.forall(_.lastModified <= output.lastModified)
		case false =>
		  input.lastModified <= output.lastModified
		}
	)
	upToDate
  }
  
  /**
   * Google Summer of Code
   *
   * Merges all the input dex files into the output file
   */
  def mergeDex(inputs: Seq[JFile], output: JFile) {
	if (!isUpToDate(inputs, output)) {
		println("(" + inputs + ") => [DEX MERGER FAST] => (" + output + ")")
		val lb = new ListBuffer[DexBuffer]
		
		for (file <- inputs) {
		  if (file.isFile && file.canRead && file.getName.endsWith(".dex")) {
			lb += new DexBuffer(file)
		  }
		}
		
		val dexFiles = lb.toList
		
		if (dexFiles.length <= 0) {
		  return
		} else if (dexFiles.length == 1) {
		  val dexThis = dexFiles.head
		  dexThis.writeTo(output)
		  return
		} else {
		  var dexThis = dexFiles.head
		  val others = dexFiles.tail
		  
		  for (dexOther <- others) {
			val dexMerger = new DexMerger(dexThis, dexOther, CollisionPolicy.KEEP_FIRST)
			dexThis = dexMerger.merge
		  }
		  
		  dexThis.writeTo(output)
		  return
		}
	}	
  }
  
  private def dxTask: Project.Initialize[Task[File]] =
    (dxPath, dxInputs, dxOpts, proguardOptimizations, classDirectory, classesDexPath, scalaInstance, streams, devDxSettings) map {
    (dxPath, dxInputs, dxOpts, proguardOptimizations, classDirectory, classesDexPath, scalaInstance, streams, devDxSettings) =>
	
	  //------------------------------------------------------------------------\\
	  //        Modified Google Summer of Code Android Plugin functions         \\
	  //------------------------------------------------------------------------\\
	  
	  /**
	   * Google Summer of Code
	   *
	   * Performs dexing on the input files, storing the result in the output file.
	   * Dexing is not executed if all the input files are up to date in regatd to the output file.
	   */
	  def dexing(inputs: Seq[JFile], output: JFile) {
	    val upToDate = isUpToDate(inputs, output)
		
		if (!upToDate) {
		  val noLocals = if (proguardOptimizations.isEmpty) ""
		    else "--no-locals"
			
		  val dxCmd = (
								Seq(dxPath.absolutePath,
										dxMemoryParameter(dxOpts._1),
										"--dex", noLocals,
										"--num-threads="+java.lang.Runtime.getRuntime.availableProcessors,
										"--output="+output.getAbsolutePath
								) ++
								inputs.map(_.absolutePath)
		  ).filter(_.length > 0)
		  
		  streams.log.debug(dxCmd.mkString(" "))
		  streams.log.info("[GSoC] Dexing "+output.getAbsolutePath)
		  streams.log.debug(dxCmd !!)
		} else {
		  streams.log.debug("dex file " + output.getAbsolutePath + " uptodate, skipping")
		}
	  }
	  
	  println("[DX INPUTS]\n\t" + dxInputs.mkString("\n\t") + "\n\n")
	  
	  val appDexPath = devDxSettings(0)
	  val clsJarPath = devDxSettings(1)
	  
	  if (incrementAppOnly == true) {
		dexing(Seq(classDirectory), appDexPath)
		mergeDex(Seq(appDexPath, classesDexPath), classesDexPath)
	  } else {
		// dexing(Seq(clsJarPath), classesDexPath)
		// dexing(filesToFinder(dxInputs).get, classesDexPath)
		
		dxOpts._2 match {
        case None =>
          dexing(filesToFinder(dxInputs).get, classesDexPath)
        case Some(predex) =>
          val (dexFiles, predexFiles) = predex match {
            case exceptSeq: Seq[_] if exceptSeq.nonEmpty =>
              val (filtered, orig) = filesToFinder(dxInputs).get.partition(file =>
              exceptSeq.exists(filter => {
                streams.log.debug("apply filter \"" + filter + "\" to \"" + file.getAbsolutePath + "\"")
                file.getAbsolutePath.matches(filter)
              }))
              // dex only classes directory ++ filtered, predex all other
              (((classDirectory --- scalaInstance.libraryJar).get ++ filtered), orig)
            case _ =>
              // dex only classes directory, predex all other
              ((classDirectory --- scalaInstance.libraryJar).get, (dxInputs --- classDirectory).get)
          }
          dexFiles.foreach(s => streams.log.debug("pack in dex \"" + s.getName + "\""))
          predexFiles.foreach(s => streams.log.debug("pack in predex \"" + s.getName + "\""))
          // dex
          dexing(dexFiles, classesDexPath)
          // predex
          filesToFinder(predexFiles).get.foreach(f => {
            val predexPath = new JFile(classesDexPath.getParent, "predex")
            if (!predexPath.exists)
              predexPath.mkdir
            val output = new File(predexPath, f.getName)
            val outputPermissionDescriptor = new File(predexPath, f.getName.replaceFirst(".jar$", ".xml"))
            dexing(Seq(f), output)
            val permission = <permissions><library name={ f.getName.replaceFirst(".jar$", "") } file={ "/data/" + f.getName } /></permissions>
            val p = new java.io.PrintWriter(outputPermissionDescriptor)
            try { p.println(permission) } finally { p.close() }
          })
		}
		
	  }

      classesDexPath
    }
	
  /**
   * Google Summer of Code
   *
   * Runs ProGuard on all inputs, storing the result to the output file.
   */
  private def proguardTask: Project.Initialize[Task[Option[File]]] = 
	(useProguard, proguardOptimizations, classDirectory, proguardInJars, streams, libraryJarPath, manifestPackage, proguardOption, devPgSettings) map { 
	(useProguard, proguardOptimizations, classDirectory, proguardInJars, streams, libraryJarPath, manifestPackage, proguardOption, devPgSettings) =>

	  val appDexPath = devPgSettings(0)
	  val clsJarPath = devPgSettings(1)
	  val clsDexPath = devPgSettings(2)
	  
	  var upToDate = isUpToDate(Seq(classDirectory), clsDexPath)
	  if (appDexPath.exists) {
		upToDate = upToDate && isUpToDate(Seq(classDirectory), appDexPath)
	  }
	  
	  var keepArgs = "";
	  
	  if (useProguard && !upToDate) {
		
		if (clsJarPath.exists) {
			var dl = new DependencyLister(clsJarPath.absolutePath)
			var allDeps = asMutableScalaMultiMap(dl.getClassesMethods)
			dl = new DependencyLister(classDirectory.absolutePath)
			var deps = asMutableScalaMultiMap(dl.getClassesMethods)
			
			if (!isSubset(deps, allDeps)) {
				allDeps = depsUnion(allDeps, deps)
				keepArgs = getProGuardKeepArgs(allDeps)
				incrementAppOnly = false
				streams.log.info("[GSoC] New classes/methods detected. Trying to run ProGuard over entire project")
			} else {
				incrementAppOnly = true
				streams.log.info("[GSoC] No new classes/methods detected. Trying to dex android-app classes only and merge with existing classes.dex")
			}
		
		}
		
		if (incrementAppOnly == true) {
			streams.log.info("]Android:dev-install-device]	Skipping Proguard")
			None
		} else {
		
			if (!clsDexPath.exists) {
				streams.log.info("[GSoC] First time build. Trying to run ProGuard over entire project")
			}
			
			val optimizationOptions = if (proguardOptimizations.isEmpty) Seq("-dontoptimize") else proguardOptimizations
			val manifestr = List("!META-INF/MANIFEST.MF", "R.class", "R$*.class", "TR.class", "TR$.class", "library.properties")
			val sep = JFile.pathSeparator
			val inJars = ("\"" + classDirectory.absolutePath + "\"") +: proguardInJars.map("\"" + _ + "\""+manifestr.mkString("(", ",!**/", ")"))

			val androidApplicationInput = Seq("\"" + classDirectory.absolutePath + "\"")
			val scalaLibraryInput = proguardInJars.map("\"" + _ + "\""+manifestr.mkString("(", ",!**/", ")"))
			
			val args = (
				"-injars" :: inJars.mkString(sep) ::
				"-outjars" :: "\""+clsJarPath.absolutePath+"\"" ::
				"-libraryjars" :: libraryJarPath.map("\""+_+"\"").mkString(sep) ::
				Nil) ++
				optimizationOptions ++ (
				"-dontwarn" :: "-dontobfuscate" :: "-keeppackagenames **" ::
				"-dontnote scala.Enumeration" ::
				"-dontnote org.xml.sax.EntityResolver" ::
				"-keep public class * extends android.app.Activity" ::
				"-keep public class * extends android.app.Service" ::
				"-keep public class * extends android.app.backup.BackupAgent" ::
				"-keep public class * extends android.appwidget.AppWidgetProvider" ::
				"-keep public class * extends android.content.BroadcastReceiver" ::
				"-keep public class * extends android.content.ContentProvider" ::
				"-keep public class * extends android.view.View" ::
				"-keep public class * extends android.app.Application" ::
				"-keep public class "+manifestPackage+".** { public protected *; }" ::
				"-keep public class * implements junit.framework.Test { public void test*(); }" ::
				"-keep class scala.Function1 { *; }" ::
				"-keep class scala.ScalaObject { *; }" ::
				"""
				 -keepclassmembers class * implements java.io.Serializable {
				   private static final java.io.ObjectStreamField[] serialPersistentFields;
				   private void writeObject(java.io.ObjectOutputStream);
				   private void readObject(java.io.ObjectInputStream);
				   java.lang.Object writeReplace();
				   java.lang.Object readResolve();
				  }
				  """ :: keepArgs ::
				proguardOption :: Nil )
			
			val config = new ProGuardConfiguration
			new ConfigurationParser(args.toArray[String]).parse(config)
			streams.log.debug("executing proguard: "+args.mkString("\n"))
			new ProGuard(config).execute
			Some(clsJarPath)
		}
	} else {
		streams.log.info("[android:dev-install-device]	Skipping Proguard")
		None
	}
  }

  private def devPackageTask(debug: Boolean):Project.Initialize[Task[File]] = (packageConfig, streams) map { (c, s) =>
    val builder = new ApkBuilder(c, debug)
    builder.build.fold(sys.error(_), s.log.info(_))
    s.log.debug(builder.outputStream.toString)
    c.packageApkPath
  }
  
  lazy val devInstallerTasks = Seq (
    devInstallDevice <<= devInstallTask(emulator = false) dependsOn devPackageDebug,
	devInstallEmulator <<= devInstallTask(emulator = true) dependsOn devPackageDebug
  )

  lazy val devSettings: Seq[Setting[_]] = inConfig(Android) (devInstallerTasks ++ Seq (
    uninstallEmulator <<= devUninstallTask(emulator = true),
    uninstallDevice <<= devUninstallTask(emulator = false),

    makeAssetPath <<= directory(mainAssetsPath),

    aaptPackage <<= aaptPackageTask,
    aaptPackage <<= aaptPackage dependsOn (makeAssetPath, dx),
    dx <<= dxTask,
    dxInputs <<= (proguard, proguardInJars, scalaInstance, classDirectory) map {
      (proguard, proguardInJars, scalaInstance, classDirectory) =>
      proguard match {
         case Some(file) => Seq(file)
         case None => (classDirectory +++ proguardInJars --- scalaInstance.libraryJar) get
      }
    },

    cleanApk <<= (packageApkPath) map (IO.delete(_)),

    proguard <<= proguardTask,
    proguard <<= proguard dependsOn (compile in Compile),

    packageConfig <<=
      (toolsPath, packageApkPath, resourcesApkPath, classesDexPath,
       nativeLibrariesPath, managedNativePath, dxInputs, resourceDirectory) map
      (ApkConfig(_, _, _, _, _, _, _, _)),

    devPackageDebug <<= devPackageTask(true),
    devPackageRelease <<= devPackageTask(false)
  ) ++ Seq(devPackageDebug, devPackageRelease).map {
    t => t <<= t dependsOn (cleanApk, aaptPackage, copyNativeLibraries)
  })
}
