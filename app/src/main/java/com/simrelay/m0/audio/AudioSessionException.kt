package com.simrelay.m0.audio

sealed class AudioSessionException(message: String) : RuntimeException(message)

class AudioSessionClosedException(direction: String) :
    AudioSessionException("$direction session is closed")

class AudioReadException(errorCode: Int) :
    AudioSessionException("AudioRecord read failed with code $errorCode")

class AudioWriteException(errorCode: Int) :
    AudioSessionException("AudioTrack write failed with code $errorCode")

class AudioZeroWriteException :
    AudioSessionException("AudioTrack wrote zero samples before the frame was complete")

class AudioWriteContractException(written: Int, requested: Int) :
    AudioSessionException("AudioTrack reported $written samples written for a $requested sample request")
