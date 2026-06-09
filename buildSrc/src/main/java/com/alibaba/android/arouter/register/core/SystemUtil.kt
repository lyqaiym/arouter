package com.alibaba.android.arouter.register.core

import com.android.build.gradle.AppPlugin
import org.gradle.api.Project
import java.io.File

/**
 * Created by gaowei on 2018/11/14
 */
object SystemUtil {

    lateinit var project: Project
    lateinit var cacheDir: File
    var isWindow = false

    fun confirm(project: Project) {
        if (!project.plugins.hasPlugin(AppPlugin::class.java)) {
            throw RuntimeException("DRouterPlugin: please apply \'com.android.application\' first")
        }

        SystemUtil.project = project
        cacheDir = File(project.buildDir, "intermediates/arouter")
        isWindow = System.getProperty("os.name").startsWith("Windows")
        if(!cacheDir.exists()){
            cacheDir.mkdirs()
        }
    }
}