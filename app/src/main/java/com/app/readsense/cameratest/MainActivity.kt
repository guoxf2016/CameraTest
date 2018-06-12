package com.app.readsense.cameratest

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.*
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.view.View
import io.fotoapparat.Fotoapparat
import io.fotoapparat.configuration.CameraConfiguration
import io.fotoapparat.log.logcat
import io.fotoapparat.selector.front
import io.fotoapparat.view.CameraView
import kotlinx.android.synthetic.main.activity_main.*
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLSurface
import javax.microedition.khronos.opengles.GL10

class MainActivity : AppCompatActivity() {

    companion object {
        const val TAG = "MainActivity"
        const val MSG_RENDER = 1
        const val MSG_INIT_EGL = 2
        const val MSG_INIT_TEX = 3

    }

    private var permissionsGranted: Boolean = false

    private lateinit var permissionsDelegate: PermissionsDelegate

    private var fotoapparat: Fotoapparat? = null

    private var cameraView0: CameraView? = null

    private val textureIds: IntArray = IntArray(1)

    private var mHandler: MyHandler? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        permissionsDelegate = PermissionsDelegate(this)
        permissionsGranted = permissionsDelegate.hasCameraPermission()
        val handlerThread = HandlerThread("Handler Thread")
        handlerThread.start()
        mHandler = MyHandler(WeakReference(this), handlerThread.looper)
        if (permissionsGranted) {
            cameraView.visibility = View.VISIBLE
            cameraView0 = CameraView(this)
            initTexture()
            initFotoapparat()
            cameraView.mSurfaceTextureListener = object : CameraView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture?, width: Int, height: Int) {
                    val message = Message()
                    message.what = MSG_INIT_EGL
                    message.obj = surface!!
                    mHandler?.sendMessage(message)
                }

                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture?, width: Int, height: Int) {
                }

                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture?): Boolean {
                    return true
                }

                override fun onSurfaceTextureUpdated(surface: SurfaceTexture?) {
                }

            }
        } else {
            permissionsDelegate.requestCameraPermission()
        }
    }

    override fun onResume() {
        super.onResume()
        if (permissionsGranted) {
            fotoapparat?.start()
        }
    }

    override fun onPause() {
        super.onPause()
        if (permissionsGranted) {
            fotoapparat?.stop()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (permissionsDelegate.resultGranted(requestCode, permissions, grantResults)) {
            permissionsGranted = true
            cameraView.visibility = View.VISIBLE
            initFotoapparat()
        }
    }

    private fun initFotoapparat() {
        if (fotoapparat != null) return
        fotoapparat = Fotoapparat(
                context = this,
                view = cameraView0!!,
                focusView = focusView,
                logger = logcat(),
                lensPosition = front(),
                cameraConfiguration = CameraConfiguration.default(),
                cameraErrorCallback = { Log.e(TAG, "Camera error: ", it) }
        )
    }

    private fun initTexture() {
        GLES20.glGenTextures(1, textureIds, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureIds[0])
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GL10.GL_TEXTURE_MIN_FILTER, GL10.GL_NEAREST.toFloat())
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GL10.GL_TEXTURE_MAG_FILTER, GL10.GL_LINEAR.toFloat())
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GL10.GL_TEXTURE_WRAP_S, GL10.GL_CLAMP_TO_EDGE.toFloat())
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GL10.GL_TEXTURE_WRAP_T, GL10.GL_CLAMP_TO_EDGE.toFloat())
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        cameraView0?.surfaceTexture = SurfaceTexture(textureIds[0])
        cameraView0?.surfaceTexture?.setOnFrameAvailableListener {
            mHandler?.sendEmptyMessage(MSG_RENDER)
        }
    }

    private var transformMatrix: FloatArray = FloatArray(16)

    private fun drawFrame() {
        //更新预览图像
        if (cameraView0?.surfaceTexture != null) {
            cameraView0?.surfaceTexture!!.updateTexImage()
            cameraView0?.surfaceTexture!!.getTransformMatrix(transformMatrix)
        }
        //指定mEGLContext为当前系统的EGL上下文，这里比较消耗性能
        mEgl?.eglMakeCurrent(mEGLDisplay, mEglSurface, mEglSurface, mEGLContext)
        //设置视口
        GLES20.glViewport(0, 0, cameraView.width, cameraView.height)
        //清楚屏幕
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        //绘制图像
        drawTexture(transformMatrix)
        //交换缓冲区，Android使用双缓冲机制，我们绘制的都是在后台缓冲区，通过交换将后台缓冲区变为前台显示区，下一帧的绘制仍然在后台缓冲区
        mEgl?.eglSwapBuffers(mEGLDisplay, mEglSurface)
    }

    private fun drawTexture(transformMatrix: FloatArray) {
        val aPositionLocation = GLES20.glGetAttribLocation(mShaderProgram!!, "aPosition")
        val aTextureCoordLocation = GLES20.glGetAttribLocation(mShaderProgram!!, "aTextureCoordinate")
        val uTextureMatrixLocation = GLES20.glGetUniformLocation(mShaderProgram!!, "uTextureMatrix")
        val uTextureSamplerLocation = GLES20.glGetUniformLocation(mShaderProgram!!, "uTextureSampler")

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureIds[0])
        GLES20.glUniform1i(uTextureSamplerLocation, 0)
        GLES20.glUniformMatrix4fv(uTextureMatrixLocation, 1, false, transformMatrix, 0)

        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(aPositionLocation)
        GLES20.glVertexAttribPointer(aPositionLocation, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)

        vertexBuffer.position(2)
        GLES20.glEnableVertexAttribArray(aTextureCoordLocation)
        GLES20.glVertexAttribPointer(aTextureCoordLocation, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
    }

    //定义所需变量
    private var mEgl: EGL10? = null
    private var mEGLDisplay = EGL10.EGL_NO_DISPLAY
    private var mEGLContext = EGL10.EGL_NO_CONTEXT
    private val mEGLConfig = arrayOfNulls<EGLConfig>(1)
    private var mEglSurface: EGLSurface? = null

    private fun initEGL(surfaceTexture: SurfaceTexture) {
        //获取系统的EGL对象
        mEgl = EGLContext.getEGL() as EGL10

        //获取显示设备
        mEGLDisplay = mEgl?.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
        if (mEGLDisplay === EGL10.EGL_NO_DISPLAY) {
            throw RuntimeException("eglGetDisplay failed! " + mEgl?.eglGetError())
        }

        //version中存放当前的EGL版本号，版本号即为version[0].version[1]，如1.0
        val version = IntArray(2)

        //初始化EGL
        if (!mEgl!!.eglInitialize(mEGLDisplay, version)) {
            throw RuntimeException("eglInitialize failed! " + mEgl!!.eglGetError())
        }

        //构造需要的配置列表
        val attributes = intArrayOf(
                //颜色缓冲区所有颜色分量的位数
                EGL10.EGL_BUFFER_SIZE, 32,
                //颜色缓冲区R、G、B、A分量的位数
                EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_ALPHA_SIZE, 8, EGL10.EGL_NONE)
        val configsNum = IntArray(1)

        //EGL根据attributes选择最匹配的配置
        if (!mEgl!!.eglChooseConfig(mEGLDisplay, attributes, mEGLConfig, 1, configsNum)) {
            throw RuntimeException("eglChooseConfig failed! " + mEgl!!.eglGetError())
        }


        //根据SurfaceTexture创建EGL绘图表面
        mEglSurface = mEgl?.eglCreateWindowSurface(mEGLDisplay, mEGLConfig[0], surfaceTexture, null)

        //指定哪个版本的OpenGL ES上下文，本文为OpenGL ES 2.0
        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL10.EGL_NONE)
        //创建上下文，EGL10.EGL_NO_CONTEXT表示不和别的上下文共享资源
        mEGLContext = mEgl?.eglCreateContext(mEGLDisplay, mEGLConfig[0], EGL10.EGL_NO_CONTEXT, contextAttribs)

        if (mEGLDisplay === EGL10.EGL_NO_DISPLAY || mEGLContext === EGL10.EGL_NO_CONTEXT) {
            throw RuntimeException("eglCreateContext fail failed! " + mEgl!!.eglGetError())
        }

        //指定mEGLContext为当前系统的EGL上下文，你可能发现了使用两个mEglSurface，第一个表示绘图表面，第二个表示读取表面
        if (!mEgl!!.eglMakeCurrent(mEGLDisplay, mEglSurface, mEglSurface, mEGLContext)) {
            throw RuntimeException("eglMakeCurrent failed! " + mEgl!!.eglGetError())
        }

        vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        mShaderProgram = linkProgram(vertexShader!!, fragmentShader!!)
    }

    private val VERTEX_SHADER = "" +
            //顶点坐标
            "attribute vec4 aPosition;\n" +
            //纹理矩阵
            "uniform mat4 uTextureMatrix;\n" +
            //自己定义的纹理坐标
            "attribute vec4 aTextureCoordinate;\n" +
            //传给片段着色器的纹理坐标
            "varying vec2 vTextureCoord;\n" +
            "void main()\n" +
            "{\n" +
            //根据自己定义的纹理坐标和纹理矩阵求取传给片段着色器的纹理坐标
            "  vTextureCoord = (uTextureMatrix * aTextureCoordinate).xy;\n" +
            "  gl_Position = aPosition;\n" +
            "}\n"

    private val FRAGMENT_SHADER = "" +
            //使用外部纹理必须支持此扩展
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            //外部纹理采样器
            "uniform samplerExternalOES uTextureSampler;\n" +
            "varying vec2 vTextureCoord;\n" +
            "void main() \n" +
            "{\n" +
            //获取此纹理（预览图像）对应坐标的颜色值
            "  vec4 vCameraColor = texture2D(uTextureSampler, vTextureCoord);\n" +
            //求此颜色的灰度值
            "  float fGrayColor = (0.3*vCameraColor.r + 0.59*vCameraColor.g + 0.11*vCameraColor.b);\n" +
            //将此灰度值作为输出颜色的RGB值，这样就会变成黑白滤镜
            "  gl_FragColor = vec4(fGrayColor, fGrayColor, fGrayColor, 1.0);\n" +
            "}\n"

    //每行前两个值为顶点坐标，后两个为纹理坐标
    private val vertexData = floatArrayOf(
            1f, 1f, 1f, 1f,
            -1f, 1f, 0f, 1f,
            -1f, -1f, 0f, 0f,
            1f, 1f, 1f, 1f,
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f)

    private val vertexBuffer: FloatBuffer by lazy { createBuffer(vertexData) }

    private fun createBuffer(vertexData: FloatArray): FloatBuffer {
        val buffer = ByteBuffer.allocateDirect(vertexData.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
        buffer.put(vertexData, 0, vertexData.size).position(0)
        return buffer
    }

    //加载着色器，GL_VERTEX_SHADER代表生成顶点着色器，GL_FRAGMENT_SHADER代表生成片段着色器
    private fun loadShader(type: Int, shaderSource: String): Int {
        //创建Shader
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) {
            throw RuntimeException("Create Shader Failed!" + GLES20.glGetError())
        }
        //加载Shader代码
        GLES20.glShaderSource(shader, shaderSource)
        //编译Shader
        GLES20.glCompileShader(shader)
        return shader
    }

    //将两个Shader链接至program中
    private fun linkProgram(verShader: Int, fragShader: Int): Int {
        //创建program
        val program = GLES20.glCreateProgram()
        if (program == 0) {
            throw RuntimeException("Create Program Failed!" + GLES20.glGetError())
        }
        //附着顶点和片段着色器
        GLES20.glAttachShader(program, verShader)
        GLES20.glAttachShader(program, fragShader)
        //链接program
        GLES20.glLinkProgram(program)
        //告诉OpenGL ES使用此program
        GLES20.glUseProgram(program)
        return program
    }

    private var vertexShader: Int? = null
    private var fragmentShader: Int? = null
    private var mShaderProgram: Int? = null


    class MyHandler(private val mOuter: WeakReference<MainActivity>,
                    private val myLooper: Looper) : Handler(myLooper) {


        override fun handleMessage(msg: Message?) {
            val outer: MainActivity? = mOuter.get()
            outer?.let {
                when (msg?.what) {
                    MSG_RENDER -> {
                        outer.drawFrame()
                    }
                    MSG_INIT_EGL -> {
                        outer.initEGL(msg.obj as SurfaceTexture)
                    }
                    MSG_INIT_TEX -> {
                        outer.initTexture()
                    }
                }
            }
        }
    }
}
