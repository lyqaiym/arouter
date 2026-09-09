package com.alibaba.android.arouter.register.core

import com.alibaba.android.arouter.register.utils.ScanSetting
import javassist.ClassPool
import org.gradle.api.DefaultTask
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.io.InputStream
import java.util.jar.JarEntry
import java.util.jar.JarFile

/**
 * 扫描编译产物（CLASSES），把实现路由接口的类清单序列化为 JSON，写入 assets 源目录。
 * 通过 variant.sources.assets.addGeneratedSourceDirectory 接入，保证 merge assets 之前执行，
 * 因此第一次构建即可打包进 APK。
 */
abstract class GenerateRoutesJsonTask : DefaultTask() {

    @get:InputFiles
    abstract val allJars: ListProperty<RegularFile>

    @get:InputFiles
    abstract val allDirectories: ListProperty<Directory>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val targets = RegisterTransform.registerList
            .filter { it.interfaceName.isNotBlank() }
            .map { it.interfaceName.replace("/", ".") }
        val collected = LinkedHashMap<String, MutableList<String>>()
        targets.forEach { collected[it] = mutableListOf() }

        val pool = ClassPool()
        allJars.get().forEach { scanJar(pool, it.asFile, targets, collected) }
        allDirectories.get().forEach { scanDirectory(pool, it.asFile, targets, collected) }

        writeJson(collected, outputDir.get().asFile)
    }

    private fun scanJar(
        pool: ClassPool,
        jarFile: File,
        targets: List<String>,
        collected: MutableMap<String, MutableList<String>>
    ) {
        if (!jarFile.exists()) return
        JarFile(jarFile).use { file ->
            val entries = file.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement() as JarEntry
                val name = entry.name
                if (name.endsWith(".class") &&
                    (name.startsWith(ScanSetting.ROUTER_CLASS_PACKAGE_NAME) || name.contains("ARouter$$"))
                ) {
                    file.getInputStream(entry).use { input ->
                        matchInterfaces(pool, input, targets, collected)
                    }
                }
            }
        }
    }

    private fun scanDirectory(
        pool: ClassPool,
        dir: File,
        targets: List<String>,
        collected: MutableMap<String, MutableList<String>>
    ) {
        if (!dir.exists()) return
        dir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".class") }
            .forEach { file ->
                file.inputStream().use { input ->
                    matchInterfaces(pool, input, targets, collected)
                }
            }
    }

    private fun matchInterfaces(
        pool: ClassPool,
        input: InputStream,
        targets: List<String>,
        collected: MutableMap<String, MutableList<String>>
    ) {
        val ctClass = pool.makeClass(input)
        val className = ctClass.name
        val interfaceNames = ctClass.classFile.interfaces.map { it.replace('/', '.') }
        targets.forEach { target ->
            if (target in interfaceNames) {
                collected[target]?.let { list ->
                    if (!list.contains(className)) list.add(className)
                }
            }
        }
    }

    private fun writeJson(collected: Map<String, MutableList<String>>, outDir: File) {
        outDir.mkdirs()
        outDir.listFiles()?.filter { it.isFile && it.name.endsWith(".json") }?.forEach { it.delete() }
        val items = collected.filter { it.value.isNotEmpty() }
        val json = StringBuilder().apply {
            append("[\n")
            items.entries.forEachIndexed { index, (interfaceName, classes) ->
                append("  {\n")
                append("    \"interfaceName\": \"$interfaceName\",\n")
                append("    \"classes\": [")
                classes.forEachIndexed { i, className ->
                    append(if (i == 0) "\n" else ",\n")
                    append("      \"$className\"")
                }
                append("\n    ]")
                append("\n  }")
                if (index != items.size - 1) append(",")
                append("\n")
            }
            append("]\n")
        }.toString()
        val aroute_reg = File(outDir, "aroute_reg")
        if (!aroute_reg.exists()) {
            aroute_reg.mkdirs()
        }
        val dest = File(aroute_reg, "${project.name}.json")
        dest.writeText(json)
        System.err.println("GenerateRoutesJsonTask: wrote ${items.size} interfaces -> ${dest.absolutePath}")
    }
}
