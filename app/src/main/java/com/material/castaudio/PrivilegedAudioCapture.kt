package com.material.castaudio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord

class PrivilegedAudioCapture(private val context: Context) {
    private var audioPolicy: Any? = null
    private var audioRecord: AudioRecord? = null

    fun startCapture(sampleRate: Int, channelConfig: Int): AudioRecord? {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

            // 1. AudioMixingRule.Builder
            val ruleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
            val ruleBuilder = ruleBuilderClass.getDeclaredConstructor().newInstance()
            val addRuleMethod = ruleBuilderClass.getMethod(
                "addRule",
                AudioAttributes::class.java,
                Int::class.javaPrimitiveType
            )

            // RULE_MATCH_ATTRIBUTE_USAGE = 1
            val mediaAttr = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build()
            val gameAttr = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .build()
            val unknownAttr = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            addRuleMethod.invoke(ruleBuilder, mediaAttr, 1)
            addRuleMethod.invoke(ruleBuilder, gameAttr, 1)
            addRuleMethod.invoke(ruleBuilder, unknownAttr, 1)

            val mixingRule = ruleBuilderClass.getMethod("build").invoke(ruleBuilder)

            // 2. AudioFormat
            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .build()

            // 3. AudioMix.Builder
            val audioMixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix\$Builder")
            val audioMixingRuleClass = Class.forName("android.media.audiopolicy.AudioMixingRule")
            val audioMixBuilder = audioMixBuilderClass.getDeclaredConstructor(audioMixingRuleClass)
                .newInstance(mixingRule)

            audioMixBuilderClass.getMethod("setFormat", AudioFormat::class.java)
                .invoke(audioMixBuilder, audioFormat)
            // ROUTE_FLAG_LOOP_BACK = 2
            audioMixBuilderClass.getMethod("setRouteFlags", Int::class.javaPrimitiveType)
                .invoke(audioMixBuilder, 2)

            val audioMix = audioMixBuilderClass.getMethod("build").invoke(audioMixBuilder)

            // 4. AudioPolicy.Builder
            val policyBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")
            val policyBuilder = policyBuilderClass.getDeclaredConstructor(Context::class.java)
                .newInstance(context)

            val audioMixClass = Class.forName("android.media.audiopolicy.AudioMix")
            policyBuilderClass.getMethod("addMix", audioMixClass).invoke(policyBuilder, audioMix)

            val policy = policyBuilderClass.getMethod("build").invoke(policyBuilder)
            this.audioPolicy = policy

            // 5. AudioManager.registerAudioPolicy(policy)
            val audioPolicyClass = Class.forName("android.media.audiopolicy.AudioPolicy")
            val regResult = audioManager.javaClass.getMethod("registerAudioPolicy", audioPolicyClass)
                .invoke(audioManager, policy) as Int

            if (regResult != 0) { // AudioManager.SUCCESS = 0
                println("registerAudioPolicy returned error code: $regResult")
                return null
            }

            // 6. AudioPolicy.createAudioRecordSink(audioMix)
            val record = audioPolicyClass.getMethod("createAudioRecordSink", audioMixClass)
                .invoke(policy, audioMix) as AudioRecord

            this.audioRecord = record
            if (record.state == AudioRecord.STATE_INITIALIZED) {
                record.startRecording()
                return record
            } else {
                record.release()
                return null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            stopCapture()
            return null
        }
    }

    fun stopCapture() {
        try {
            audioRecord?.apply {
                try { stop() } catch (_: Exception) {}
                try { release() } catch (_: Exception) {}
            }
            audioRecord = null

            audioPolicy?.let { policy ->
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val audioPolicyClass = Class.forName("android.media.audiopolicy.AudioPolicy")
                audioManager.javaClass.getMethod("unregisterAudioPolicy", audioPolicyClass)
                    .invoke(audioManager, policy)
            }
            audioPolicy = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
