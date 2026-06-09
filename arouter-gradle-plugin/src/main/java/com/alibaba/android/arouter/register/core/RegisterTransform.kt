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
abstract class RegisterTransform @Inject constructor(
    private val androidComponents: AndroidComponentsExtension<*, *, *>
) : DefaultTask() {
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
        val outputFile = output.get().asFile
        val jarOutput = JarOutputStream(BufferedOutputStream(FileOutputStream(outputFile)))
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
                    if (file.isFile() && ScanUtil.shouldProcessClass(path)) {
                        ScanUtil.scanClass(pool, file)
                    }
                    jarOutput.closeEntry()
                }
            }
        }
        jarOutput.close()
        if (fileContainsInitClass != null) {
            fileContainsInitClass = outputFile // 👈 就加这一行！！！
            registerList.forEach { ext ->
                if (ext.interfaceName.isNotEmpty()) {
                    val classname = ext.interfaceName.replace("/", ".")
                    System.err.println("packOutputJar:interfaceName=${classname},size=${ext.classList.size}")
                    for (classNameFind in ext.classList) {
                        System.err.println("packOutputJar:classNameF=${classNameFind}")
                    }
                    RegisterCodeGenerator.insertInitCodeTo(ext)
                }
            }
        }
    }

//    private fun packOutputJar2() {
//        // 🔥 正确顺序：
//// 1. 先扫描所有 jar/class（收集需要注册的类）
//// 2. 再打包输出 Jar
//// 3. 最后对目标 Jar 插入初始化代码
//        val outputFile = output.get().asFile
//        val jarOutput = JarOutputStream(BufferedOutputStream(FileOutputStream(outputFile)))
//        val insertTag = mutableSetOf<String>()
//
//// 第一步：先遍历所有输入文件，【只扫描，不写入】
//        inputFiles.forEach { input ->
//            if (input.isFile && input.name.endsWith(".jar", true)) {
//                val jarFile = JarFile(input)
//                for (jarEntry in jarFile.entries()) {
//                    if (!insertTag.contains(jarEntry.name)) {
//                        if (ScanUtil.shouldProcessPreDexJar(input.path)) {
//                            // 🔥 先扫描收集类，不写文件
//                            ScanUtil.scanJar(pool, input)
//                        }
//                    }
//                }
//                jarFile.close()
//            } else {
//                input.walk().filter { it.isFile }.forEach { file ->
//                    if (file.name.endsWith(".class") && ScanUtil.shouldProcessClass(file.path)) {
//                        // 🔥 先扫描收集类
//                        ScanUtil.scanClass(pool, file)
//                    }
//                }
//            }
//        }
//
//// 第二步：扫描完成 → 开始【写入输出 Jar】
//        inputFiles.forEach { input ->
//            if (input.isFile && input.name.endsWith(".jar", true)) {
//                val jarFile = JarFile(input)
//                for (jarEntry in jarFile.entries()) {
//                    if (!insertTag.contains(jarEntry.name)) {
//                        insertTag.add(jarEntry.name)
//                        jarOutput.putNextEntry(JarEntry(jarEntry.name))
//
//                        // 拷贝文件
//                        jarFile.getInputStream(jarEntry).use {
//                            it.copyTo(jarOutput)
//                        }
//
//                        jarOutput.closeEntry()
//                    }
//                }
//                jarFile.close()
//            } else {
//                // 处理目录
//                input.walk().filter { it.isFile }.forEach { file ->
//                    val relativePath = input.toURI().relativize(file.toURI()).path
//                    val entryPath = relativePath.replace(File.separatorChar, '/')
//
//                    if (!insertTag.contains(entryPath)) {
//                        insertTag.add(entryPath)
//                        jarOutput.putNextEntry(JarEntry(entryPath))
//                        file.inputStream().use { it.copyTo(jarOutput) }
//                        jarOutput.closeEntry()
//                    }
//                }
//            }
//        }
//
//// 第三步：必须【关闭输出流】之后，再修改 Jar！！！
//        jarOutput.flush()
//        jarOutput.close()
//
//        // 🔥🔥🔥 终极修复：注入到【最终输出的 Jar】，而不是原来的 arouter-api jar！
//        fileContainsInitClass = outputFile // 👈 就加这一行！！！
//// 🔥 关键：流关闭后，再执行代码注入！！！
//        if (fileContainsInitClass != null && fileContainsInitClass?.exists() == true) {
//            registerList.forEach { ext ->
//                if (ext.interfaceName.isNotEmpty() && ext.classList.isNotEmpty()) {
//                    println("🔥 开始注入代码：${ext.interfaceName}，类数量：${ext.classList.size}")
//                    RegisterCodeGenerator.insertInitCodeTo(ext)
//                }
//            }
//        }
//
//        println("✅ 输出 Jar 完成：${outputFile.absolutePath}")
//    }
}
