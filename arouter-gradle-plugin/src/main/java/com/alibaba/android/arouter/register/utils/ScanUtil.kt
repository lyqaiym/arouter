package com.alibaba.android.arouter.register.utils

import com.alibaba.android.arouter.register.core.RegisterTransform
import javassist.ClassPool
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.jar.JarEntry
import java.util.jar.JarFile

/**
 * Scan all class in the package: com/alibaba/android/arouter/
 * find out all routers,interceptors and providers
 * @author billy.qi email: qiyilike@163.com
 * @since 17/3/20 11:48
 */
object ScanUtil {

    /**
     * scan jar file
     * @param pool ClassPool for javassist
     * @param jarFile All jar files that are compiled into apk
     * @param destFile dest file after this transform
     */
    fun scanJar(pool: ClassPool, jarFile: File) {
        if (jarFile.exists()) {
            System.err.println("scanJar:jarFile=$jarFile")
            JarFile(jarFile).use { file ->
                val enumeration = file.entries()
                while (enumeration.hasMoreElements()) {
                    val jarEntry = enumeration.nextElement() as JarEntry
                    val entryName = jarEntry.name
                    System.err.println("scanJar:entryName=$entryName,jarFile=$jarFile")
                    if (entryName.startsWith(ScanSetting.ROUTER_CLASS_PACKAGE_NAME)) {
                        file.getInputStream(jarEntry).use { inputStream ->
                            System.err.println("scanJar:avable=${inputStream.available()}")
                            if (entryName.endsWith(".class")) {
                                scanClass(pool, inputStream)
                            }
                        }
                    } else if (ScanSetting.GENERATE_TO_CLASS_FILE_NAME == entryName) {
                        System.err.println("scanJar:LogisticsCenter")
                        // mark this jar file contains LogisticsCenter.class
                        // After the scan is complete, we will generate register code into this file
                        RegisterTransform.fileContainsInitClass = jarFile
                    }
                }
            }
        }
    }

    fun shouldProcessPreDexJar(path: String): Boolean {
        return !path.contains("com.android.support") && !path.contains("/android/m2repository")
    }

    fun shouldProcessClass(entryName: String?): Boolean {
        return !entryName.isNullOrBlank() && entryName.startsWith(ScanSetting.ROUTER_CLASS_PACKAGE_NAME)
    }

    /**
     * scan class file
     * @param pool ClassPool for javassist
     * @param file class file
     */
    fun scanClass(pool: ClassPool, file: File) {
        System.err.println("scanClass:file=$file")
        FileInputStream(file).use { inputStream ->
            scanClass(pool, inputStream)
        }
    }

    fun scanClass(pool: ClassPool, inputStream: InputStream) {
        val ctClass = pool.makeClass(inputStream)
//        System.err.println("scanClass:name=${ctClass.name}")
        RegisterTransform.registerList?.forEach { ext ->
            if (ext.interfaceName.isNotBlank()) {
                try {
                    val classes = ctClass.interfaces
                    for (i in classes.indices) {
                        val ctClassIt = classes[i]
                        val itName = ctClassIt.name
                        val classname = ext.interfaceName.replace("/", ".")
//                        System.err.println(
//                            "scanClass:itName(all)=$classname,itName=$itName" + ",equals="
//                                    + itName.equals(classname) + ",classList=${ext.classList.size}"
//                        )
                        if (itName.equals(classname)) {
                            //fix repeated inject init code when Multi-channel packaging
                            if (!ext.classList.contains(ctClass.name)) {
                                System.err.println("scanClass(add1):name=${ctClass.name}")
                                ext.classList.add(ctClass.name)
                            } else {
//                                System.err.println("scanClass(add2):name=${ctClass.name}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    System.err.println("scanClass:itName(err)=${ext.interfaceName},e=$e")
                }
            }
        }
    }
}