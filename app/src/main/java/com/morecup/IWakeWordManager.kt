package com.morecup

import android.content.Context

interface IWakeWordManager {
    /**
     * 初始化唤醒词检测器
     * @param applicationContext 应用上下文
     * @param callBack 唤醒词检测回调函数
     */
    fun init(applicationContext: Context, callBack: () -> Unit)

    /**
     * 开始唤醒词检测
     */
    fun start()

    /**
     * 停止唤醒词检测
     */
    fun stop()

    /**
     * 销毁唤醒词检测器，释放资源
     */
    fun destroy()
}