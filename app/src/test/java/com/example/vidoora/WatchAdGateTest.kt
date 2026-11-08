package com.example.vidoora

import com.example.vidoora.feature.ads.WatchAdGate
import org.junit.Assert.*
import org.junit.Test

class WatchAdGateTest {
    private class Timer {
        var action: ()->Unit={}; var cancelled=false
        fun schedule(action: ()->Unit): ()->Unit { this.action=action; cancelled=false; return { cancelled=true } }
    }
    @Test fun loadThenShowWaitsForDismissalAndForwardsOnlyOnce() {
        val timer=Timer(); val busy=mutableListOf<Boolean>(); val gate=WatchAdGate<String>(timer::schedule,{ busy.add(it); Unit })
        var loaded: (String?)->Unit={}; var close: ()->Unit={}; var shows=0; var forwards=0
        gate.open(true,{true},{loaded=it},{ad,done -> assertEquals("demo",ad); shows++; close=done},{forwards++})
        assertTrue(gate.pending); assertEquals(0,forwards)
        loaded("demo"); loaded("duplicate"); assertEquals(1,shows); assertTrue(timer.cancelled)
        timer.action(); assertEquals(0,forwards)
        close(); close(); assertEquals(1,forwards); assertFalse(gate.pending); assertEquals(listOf(true,false),busy)
    }
    @Test fun unavailableConsentNoFillAndLoadExceptionContinueWithoutShowing() {
        val timer=Timer(); val gate=WatchAdGate<String>(timer::schedule); var forwards=0
        gate.open(false,{true},{fail("Disabled request")},{_,_->fail("Disabled show")},{forwards++})
        gate.open(true,{true},{it(null)},{_,_->fail("No fill show")},{forwards++})
        gate.open(true,{true},{throw IllegalStateException("network fixture")},{_,_->fail("Failed show")},{forwards++})
        assertEquals(3,forwards); assertFalse(gate.pending)
    }
    @Test fun timeoutCannotShowALateAdOrAffectANewWatch() {
        val timer=Timer(); val gate=WatchAdGate<String>(timer::schedule); var old: (String?)->Unit={}; var next: (String?)->Unit={}; var forwards=0; var shows=0
        gate.open(true,{true},{old=it},{_,_->shows++},{forwards++})
        timer.action(); assertEquals(1,forwards)
        gate.open(true,{true},{next=it},{_,done->shows++; done()},{forwards++})
        old("late"); assertTrue(gate.pending); assertEquals(0,shows)
        next("fresh"); assertEquals(1,shows); assertEquals(2,forwards)
    }
    @Test fun doubleWatchTapDoesNotRequestOrShowTwoAds() {
        val timer=Timer(); val gate=WatchAdGate<String>(timer::schedule); var requests=0; var loaded: (String?)->Unit={}
        gate.open(true,{true},{requests++; loaded=it},{_,done->done()},{})
        gate.open(true,{true},{requests++},{_,_->fail("Duplicate")},{})
        assertEquals(1,requests); loaded(null); assertFalse(gate.pending)
    }
    @Test fun navigationOrIdentityChangeCancelsAllStaleCallbacks() {
        val timer=Timer(); val gate=WatchAdGate<String>(timer::schedule); var valid=true; var loaded: (String?)->Unit={}; var closed: ()->Unit={}; var forwards=0
        gate.open(true,{valid},{loaded=it},{_,done->closed=done},{forwards++})
        valid=false; loaded("demo"); assertFalse(gate.pending); assertEquals(0,forwards)
        valid=true
        gate.open(true,{valid},{it("demo")},{_,done->closed=done},{forwards++})
        gate.cancel(); closed(); timer.action(); assertEquals(0,forwards)
    }
    @Test fun showFailureAndSdkDuplicateDismissalContinueOnce() {
        val timer=Timer(); val gate=WatchAdGate<String>(timer::schedule); var forwards=0
        gate.open(true,{true},{it("demo")},{_,done->done(); throw IllegalStateException("show fixture")},{forwards++})
        assertEquals(1,forwards); assertFalse(gate.pending)
    }
    @Test fun eachWatchCanRequestAnAdWithoutAnotherAccountsCooldown() {
        val timer=Timer(); val gate=WatchAdGate<String>(timer::schedule); var requests=0; var shows=0; var forwards=0
        repeat(3) { gate.open(true,{true},{requests++; it("demo")},{_,done->shows++; done()},{forwards++}) }
        assertEquals(3,requests); assertEquals(3,shows); assertEquals(3,forwards)
    }
}

