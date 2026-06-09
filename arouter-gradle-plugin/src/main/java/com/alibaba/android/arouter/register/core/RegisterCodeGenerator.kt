package com.alibaba.android.arouter.register.core

import com.alibaba.android.arouter.register.utils.ScanSetting
import com.android.tools.r8.internal.Sy
import javassist.ClassPool
import javassist.CtClass
import javassist.CtMethod
import javassist.NotFoundException
import org.apache.commons.io.IOUtils
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry

class RegisterCodeGenerator(val extension: ScanSetting) {
    companion object {
        fun insertInitCodeTo(registerSetting: ScanSetting?) {
            if (registerSetting != null && registerSetting.classList.isNotEmpty()) {
                val processor = RegisterCodeGenerator(registerSetting)
                val file = RegisterTransform.fileContainsInitClass
                file?.let {
                    if (it.name.endsWith(".jar")) {
                        processor.insertInitCodeIntoJarFile(it)
                    }
                }
            }
        }
    }

    /**
     * generate code into jar file
     * @param jarFile the jar file which contains LogisticsCenter.class
     * @return
     */
    fun insertInitCodeIntoJarFile(jarFile: File): File {
        val optJar = File(jarFile.getParent(), jarFile.name + ".opt")
        if (optJar.exists())
            optJar.delete()
        val file = JarFile(jarFile)
        val enumeration = file.entries()
        val jarOutputStream = JarOutputStream(FileOutputStream(optJar))

        while (enumeration.hasMoreElements()) {
            val jarEntry = enumeration.nextElement() as JarEntry
            val entryName = jarEntry.getName()
            val zipEntry = ZipEntry(entryName)
            val inputStream = file.getInputStream(jarEntry)
            jarOutputStream.putNextEntry(zipEntry)
            if (ScanSetting.GENERATE_TO_CLASS_FILE_NAME == entryName) {

                System.out.println("Insert init code to class >> " + entryName)
                val bytes = referHackWhenInit(inputStream)
                jarOutputStream.write(bytes)
            } else {
                jarOutputStream.write(IOUtils.toByteArray(inputStream))
            }
            inputStream.close()
            jarOutputStream.closeEntry()
        }
        jarOutputStream.close()
        file.close()

        if (jarFile.exists()) {
            jarFile.delete()
        }
        optJar.renameTo(jarFile)
        System.out.println("insertInitCodeIntoJarFile:jarFile=" + jarFile)
        return jarFile
    }

    // 等价于原来的 referHackWhenInit，完全用 Javassist 实现
    private fun referHackWhenInit(inputStream: InputStream): ByteArray {
        return processTargetClass(inputStream)
    }

    /**
     * 等价于 MyClassVisitor + RouteMethodVisitor 的完整功能
     * 作用：找到类中的目标方法，并在方法 return 前注入自动注册代码
     */
    private fun processTargetClass(
        inputStream: InputStream
    ): ByteArray {
        val classPool = ClassPool.getDefault()
        val ctClass: CtClass

        try {
            ctClass = classPool.makeClass(inputStream)
        } catch (e: Throwable) {
            // 无法解析的 class，直接返回原字节码
            return inputStream.readBytes()
        }

        try {
            // 目标类名（把 / 换成 . 格式）
            val targetClassName = ScanSetting.GENERATE_TO_CLASS_NAME.replace("/", ".")

            // 如果当前类不是要插桩的类，直接返回原代码
            if (ctClass.name != targetClassName) {
                return ctClass.toBytecode()
            }

            // 找到目标方法：loadRouterMap()
            val targetMethod: CtMethod? = try {
                ctClass.getDeclaredMethod(ScanSetting.GENERATE_TO_METHOD_NAME)
            } catch (e: Exception) {
                null
            }

            targetMethod?.let { method ->
                // 生成注册代码：在 return 前插入
                val injectCode = buildInjectCode()

                // insertAfter = 所有 return 之前执行（等价 ASM visitInsn RETURN）
                method.insertAfter(injectCode)
            }

            System.out.println("插桩成功")
            // 返回修改后的字节码
            return ctClass.toBytecode()
        } catch (e: Exception) {
            System.out.println("插桩失败" + e)
            // 插桩失败，返回原字节码
            return ctClass.toBytecode()
        } finally {
            ctClass.detach() // 必须释放，防止内存溢出
        }
    }

    /**
     * 生成要注入的代码：遍历所有类，调用 register()
     */
    private fun buildInjectCode(): String {
        val sb = StringBuilder()
        extension.classList.forEach { className ->
            // 把 com/xxx/Test 换成 com.xxx.Test
            val realName = className.replace("/", ".")
            // 生成：XXX.register("类名");
            sb.appendLine(
                "${
                    ScanSetting.GENERATE_TO_CLASS_NAME.replace(
                        "/",
                        "."
                    )
                }.${ScanSetting.REGISTER_METHOD_NAME}(\"$realName\");"
            )
        }
        System.out.println("buildInjectCode:sb=" + sb)
        return sb.toString()
    }
}