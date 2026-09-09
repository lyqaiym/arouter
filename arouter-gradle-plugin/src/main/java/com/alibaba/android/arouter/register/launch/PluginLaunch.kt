package com.alibaba.android.arouter.register.launch

import com.alibaba.android.arouter.register.utils.Logger
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.gradle.AppPlugin
import com.alibaba.android.arouter.register.utils.ScanSetting
import com.alibaba.android.arouter.register.core.GenerateRoutesJsonTask
import com.alibaba.android.arouter.register.core.RegisterTransform
import com.alibaba.android.arouter.register.core.SystemUtil
import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.variant.ScopedArtifacts
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Simple version of AutoRegister plugin for ARouter
 * @author billy.qi email: qiyilike@163.com
 * @since 17/12/06 15:35
 */
public class PluginLaunch : Plugin<Project> {

    override fun apply(project: Project) {
//        def isApp = project.plugins.hasPlugin(AppPlugin)
        var isApp = project.plugins.hasPlugin(AppPlugin::class.java)
        val start = System.currentTimeMillis()
        System.out.println("arouter-register:project=${project.name},isApp=${isApp}")
        //only application module needs this plugin to generate register code
        SystemUtil.confirm(project)
        Logger.make(project)

        System.out.println("Project enable arouter-register plugin")

//      def android = project.extensions.getByType(AppExtension)
//      var android = project.extensions.getByType(AppExtension::class.java)
        //init arouter-auto-register settings
        var list: ArrayList<ScanSetting> = ArrayList()
        list.add(ScanSetting("IRouteRoot"))
        list.add(ScanSetting("IInterceptorGroup"))
        list.add(ScanSetting("IProviderGroup"))
//        list.add(ScanSetting("IRouteGroup"))
//        list.add(ScanSetting("ISyringe"))
        RegisterTransform.registerList = list
        var androidComponents =
            project.extensions.getByType(AndroidComponentsExtension::class.java)
        androidComponents.onVariants { variant ->
            System.out.println("arouter-register execute:name1=" + variant.name)
            val taskProvider = project.tasks.register(
                "${variant.name}ARouterTask", RegisterTransform::class.java, androidComponents
            )
            System.out.println("arouter-register execute:name2=" + variant.name)
            val scoped = variant.artifacts.forScope(ScopedArtifacts.Scope.ALL)
            if (isApp) {
                scoped.use(taskProvider).toTransform(
                    ScopedArtifact.CLASSES,
                    RegisterTransform::allJars,
                    RegisterTransform::allDirectories,
                    RegisterTransform::output
                )
            } else {
                val generateTask = project.tasks.register(
                    "${variant.name}GenerateRoutesJsonTask", GenerateRoutesJsonTask::class.java
                )
                scoped.use(generateTask).toGet(
                    ScopedArtifact.CLASSES,
                    GenerateRoutesJsonTask::allJars,
                    GenerateRoutesJsonTask::allDirectories
                )
                variant.sources.assets?.addGeneratedSourceDirectory(generateTask) { it.outputDir }
            }
        }
        val time = System.currentTimeMillis() - start
        System.out.println("arouter-register:project=${project.name},time=${time}")
    }

}
