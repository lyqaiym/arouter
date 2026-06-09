package com.alibaba.android.arouter.register.core

import com.alibaba.android.arouter.register.utils.ScanSetting
import com.alibaba.android.arouter.register.utils.ScanUtil
import com.android.build.api.variant.AndroidComponentsExtension
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.TaskAction

import javax.inject.Inject

import javassist.ClassPool
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Queue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import kotlin.collections.iterator

///**
// * transform api
// * <p>
// *     1. Scan all classes to find which classes implement the specified interface
// *     2. Generate register code into class file: {@link ScanSetting#GENERATE_TO_CLASS_FILE_NAME}
// * @author billy.qi email: qiyilike@163.com
// * @since 17/3/21 11:48
// */
abstract class RegisterTransform : DefaultTask() {
    //
//    Project project
//    static File fileContainsInitClass;
    private val pool = ClassPool()

    init {
        System.err.println("RegisterTransform:init:list=" + registerList.size)
        registerList.forEach { ext ->
            if (ext.interfaceName.isNotEmpty()) {
                val classname = ext.interfaceName.replace("/", ".")
                System.err.println("RegisterTransform:interfaceName=$classname")
                pool.makeClass(classname)
            }
        }
    }

    companion object {
        var registerList: ArrayList<ScanSetting> = ArrayList()
        lateinit var androidComponents: AndroidComponentsExtension<*, *, *>
        var fileContainsInitClass: File? = null
    }

    @get:InputFiles
    abstract val allJars: ListProperty<RegularFile>

    @get:InputFiles
    abstract val allDirectories: ListProperty<Directory>

    @get:OutputFile
    abstract val output: RegularFileProperty

    private val inputFiles = mutableSetOf<File>()
    private val routerDir = File(SystemUtil.cacheDir, "router")

    @TaskAction
    fun taskAction() {
        System.out.println("arouter-register taskAction.start")

        // 确保输出目录存在
        val outputFile = output.get().asFile
        outputFile.parentFile.mkdirs()

        // 创建一个空的 classes.jar 文件作为输出
        // 这是为了满足 Gradle 的 Transform 要求
        if (!outputFile.exists()) {
            outputFile.createNewFile()
        }

        System.out.println("RegisterTransform:taskAction: output file created at ${outputFile.absolutePath}")

        // 扫描类路径
        val classpath = assembleClassPath()
        System.out.println("RegisterTransform:taskAction:classpath=" + classpath.size)

        // TODO: 这里应该实现实际的类扫描和代码生成逻辑
        // 目前只是创建输出文件以满足构建要求
        packOutputJar()
    }

    @Suppress("UnstableApiUsage")
    private fun assembleClassPath(): Queue<File> {
        val classpath = ConcurrentLinkedQueue<File>()
        inputFiles.clear()
        androidComponents.sdkComponents.bootClasspath.get().forEach {
            classpath.add(it.asFile)
        }
        allJars.get().forEach {
            classpath.add(it.asFile)
            inputFiles.add(it.asFile)
        }
        allDirectories.get().forEach {
            classpath.add(it.asFile)
            inputFiles.add(it.asFile)
        }
        return classpath
    }

    private fun packOutputJar() {
        val jarOutput = JarOutputStream(BufferedOutputStream(FileOutputStream(output.get().asFile)))
        val insertTag = mutableSetOf<String>()
        inputFiles.forEach { input ->
            if (input.isFile && input.name.lowercase().endsWith(".jar")) {
                val jarFile = JarFile(input)
                for (jarEntry in jarFile.entries()) {
                    if (!insertTag.contains(jarEntry.name)) {
                        insertTag.add(jarEntry.name)
                        jarOutput.putNextEntry(JarEntry(jarEntry.name))
                        jarFile.getInputStream(jarEntry).use {
                            it.copyTo(jarOutput)
                        }
                        System.out.println("RegisterTransform:packOutputJar:input=" + input)
                        if (ScanUtil.shouldProcessPreDexJar(input.path)) {
                            ScanUtil.scanJar(pool, input)
                        }
                        jarOutput.closeEntry()
                    }
                }
                jarFile.close()
            } else {
                // input is dir
                input.walk().filter { it.isFile }.forEach { file ->
                    val relativePath = input.toURI().relativize(file.toURI()).path
                    jarOutput.putNextEntry(JarEntry(relativePath.replace(File.separatorChar, '/')))
                    file.inputStream().use { inputStream ->
                        inputStream.copyTo(jarOutput)
                    }
                    System.out.println("RegisterTransform:packOutput:file=" + file)
                    if (file.isFile() && ScanUtil.shouldProcessClass(path)) {
                        ScanUtil.scanClass(pool, file)
                    }
                    jarOutput.closeEntry()
                }
            }
        }
        jarOutput.close()
        registerList.forEach { ext ->
            if (ext.interfaceName.isNotEmpty()) {
                val classname = ext.interfaceName.replace("/", ".")
                System.err.println("packOutputJar:interfaceName=${classname},size=${ext.classList.size}")
                for (classNameFind in ext.classList) {
                    System.err.println("packOutputJar:classNameF=${classNameFind}")
                }
            }
        }
    }
}
