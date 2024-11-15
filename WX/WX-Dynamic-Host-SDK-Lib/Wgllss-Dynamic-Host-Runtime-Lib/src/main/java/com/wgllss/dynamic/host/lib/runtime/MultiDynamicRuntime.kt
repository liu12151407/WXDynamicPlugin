package com.wgllss.dynamic.host.lib.runtime

import android.text.TextUtils
import java.lang.reflect.Field
import com.wgllss.dynamic.host.lib.runtime.BuildConfig
import java.io.File

object MultiDynamicRuntime {

    /**
     * hack ContainerClassLoader到PathClassLoader之上
     * 1. ClassLoader树结构中可能包含多个ContainerClassLoader
     * 2. 在hack时，需要提供containerKey作为该插件containerApk的标识
     *
     * @param containerKey 插件业务对应的key，不随插件版本变动
     * @param containerApk 插件zip包中的runtimeApk
     */
    fun loadContainerApk(containerKey: String, containerApk: InstalledApk): Boolean {
        // 根据key去查找对应的ContainerClassLoader
        val pathClassLoader = MultiDynamicRuntime::class.java.classLoader
        val containerClassLoader = findContainerClassLoader(containerKey)
        if (containerClassLoader != null) {
            val apkFilePath = containerClassLoader.apkPath
            if (TextUtils.equals(apkFilePath, containerApk.apkFilePath)) {
                //已经加载相同版本的containerApk了,不需要加载
                return false
            } else {
                // 同个插件的ContainerClassLoader版本不一样，说明要移除老的ContainerClassLoader，插入新的
                try {
                    removeContainerClassLoader(containerClassLoader)
                } catch (e: Exception) {
                    throw RuntimeException(e)
                }
            }
        }
        // 将ContainerClassLoader hack到PathClassloader之上
        try {
            if (pathClassLoader != null) {
                hackContainerClassLoader(containerKey, containerApk, pathClassLoader)
            }
            if (BuildConfig.DEBUG) android.util.Log.e("MultiDynamicRuntime", "containerApk插入成功，containerKey= $containerKey")
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
        return true
    }

    private fun findContainerClassLoader(containerKey: String): ContainerClassLoader? {
        val current = MultiDynamicRuntime::class.java.classLoader
        var parent = current.parent
        while (parent != null) {
            if (parent is ContainerClassLoader) {
                val item = parent
                if (TextUtils.equals(item.containerKey, containerKey)) {
                    return item
                }
            }
            parent = parent.parent
        }
        return null
    }

    @Throws(Exception::class)
    private fun removeContainerClassLoader(containerClassLoader: ContainerClassLoader) {
        val pathClassLoader = MultiDynamicRuntime::class.java.classLoader
        var child = pathClassLoader
        var parent = pathClassLoader.parent
        while (parent != null) {
            if (parent === containerClassLoader) {
                break
            }
            child = parent
            parent = parent.parent
        }
        if (child != null && parent === containerClassLoader) {
            hackParentClassLoader(child, containerClassLoader.parent)
        }
    }

    @Throws(Exception::class)
    private fun hackContainerClassLoader(containerKey: String, containerApk: InstalledApk, pathClassLoader: ClassLoader) {
        File(containerApk.oDexPath).setReadOnly()
        File(containerApk.apkFilePath).setReadOnly()
        containerApk.libraryPath?.let { File(it).setReadOnly() }
        val containerClassLoader = ContainerClassLoader(
            containerKey, containerApk.apkFilePath, containerApk.oDexPath, containerApk.libraryPath, pathClassLoader.parent, pathClassLoader
        )
        hackParentClassLoader(pathClassLoader, containerClassLoader)
    }

    /**
     * 修改ClassLoader的parent
     *
     * @param classLoader          需要修改的ClassLoader
     * @param newParentClassLoader classLoader的新的parent
     * @throws Exception 失败时抛出
     */
    @Throws(Exception::class)
    fun hackParentClassLoader(classLoader: ClassLoader?, newParentClassLoader: ClassLoader?) {
        val field = getParentField() ?: throw RuntimeException("在ClassLoader.class中没找到类型为ClassLoader的parent域")
        field.isAccessible = true
        field[classLoader] = newParentClassLoader
    }

    /**
     * 安全地获取到ClassLoader类的parent域
     *
     * @return ClassLoader类的parent域.或不能通过反射访问该域时返回null.
     */
    private fun getParentField(): Field? {
        val classLoader = MultiDynamicRuntime::class.java.classLoader
        val parent = classLoader.parent
        var field: Field? = null
        for (f in ClassLoader::class.java.declaredFields) {
            try {
                val accessible = f.isAccessible
                f.isAccessible = true
                val o = f[classLoader]
                f.isAccessible = accessible
                if (o === parent) {
                    field = f
                    break
                }
            } catch (ignore: IllegalAccessException) {
            }
        }
        return field
    }
}