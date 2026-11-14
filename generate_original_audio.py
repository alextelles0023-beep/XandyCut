import os, math, subprocess, base64, random
import numpy as np
from pathlib import Path
p=Path('app/src/main/assets/audio')
cloud=Path('online_audio')
cloud.mkdir(parents=True,exist_ok=True)
(p/'offline').mkdir(parents=True,exist_ok=True)
(p/'online').mkdir(parents=True,exist_ok=True)
SR=22050
A4=440

def freq(n): return 440.0*2**((n-69)/12)

def note(sig, start, dur, pitch, amp=.2, voice='pad'):
    off=int(start*SR); n=int(dur*SR); t=np.arange(n)/SR
    if off+n>len(sig):n=len(sig)-off;t=t[:n]
    if n<=0:return
    f=freq(pitch)
    if voice=='pad':
        wave=(np.sin(2*np.pi*f*t)+.17*np.sin(2*np.pi*2*f*t)+.08*np.sin(2*np.pi*3.01*f*t)); a=np.minimum(1,t/.13)*np.minimum(1,(dur-t)/.42)
    elif voice=='bell':
        wave=(np.sin(2*np.pi*f*t)+.33*np.sin(2*np.pi*2.02*f*t)+.14*np.sin(2*np.pi*4.03*f*t)); a=(1-np.exp(-t*70))*np.exp(-t*2.1)
    elif voice=='bass':
        wave=np.sin(2*np.pi*f*t)+.2*np.sin(2*np.pi*2*f*t);a=(1-np.exp(-t*50))*np.exp(-t*2)
    elif voice=='pluck':
        wave=(np.sin(2*np.pi*f*t)+.3*np.sin(2*np.pi*2*f*t));a=np.exp(-t*3.3)
    else:
        wave=np.sin(2*np.pi*f*t);a=np.minimum(1,t/.03)*np.minimum(1,(dur-t)/.12)
    sig[off:off+n] += (amp * wave * a).astype(np.float32)

def drums(sig, beat, kind='kick', amp=.16):
    off=int(beat*SR);dur=.27 if kind=='kick' else .12;n=min(int(dur*SR),len(sig)-off)
    if n<=0:return
    t=np.arange(n)/SR
    if kind=='kick':
        wave=np.sin(2*np.pi*(95*t+(-68/4)*t*t)); env=np.exp(-t*25)
    elif kind=='hat':
        rnd=np.random.default_rng(int(beat*1000)+7);wave=rnd.normal(0,1,n);env=np.exp(-t*80)
    else:
        rnd=np.random.default_rng(int(beat*1000)+11);wave=rnd.normal(0,1,n)+.2*np.sin(2*np.pi*180*t);env=np.exp(-t*32)
    sig[off:off+n]+=amp*wave*env

def generate(name, spec, duration, folder):
    sig=np.zeros(int(SR*duration),dtype=np.float32)
    if spec['style']=='fx':
        t=np.arange(len(sig))/SR
        rng=np.random.default_rng(spec['seed']); noise=rng.normal(0,.34,len(sig))
        if spec['kind']=='whoosh':
            rise=np.minimum(1,t/.65)*np.maximum(0,1-t/duration)**2
            w=noise+np.sin(2*np.pi*(280*t+220*t*t))*0.45
            sig=rise*w
        elif spec['kind']=='impact':
            sig=np.exp(-t*8)*(np.sin(2*np.pi*65*t)+.48*noise)
        else:
            sig=.65*np.exp(-t*2)*(np.sin(2*np.pi*740*t)+.3*np.sin(2*np.pi*1110*t))
    else:
        bpm=spec['bpm'];step=60/bpm
        chords=spec['chords'];melody=spec['melody'];voice=spec.get('voice','pluck')
        for cycle,t0 in enumerate(np.arange(0,duration,4*step)):
            chord=chords[cycle%len(chords)]
            for pitch in chord:
                note(sig,t0,min(4*step,duration-t0),pitch,.11,'pad')
            for beat in range(4):
                at=t0+step*beat
                if at>=duration: break
                if spec.get('rhythm',False):
                    drums(sig,at,'kick',.12)
                    if beat in (1,3): drums(sig,at,'snare',.06)
                    if at+step/2<duration: drums(sig,at+step/2,'hat',.045)
                if beat%2==0:note(sig,at,min(step,duration-at),chord[0]-12,.13,'bass')
            for k in range(8):
                at=t0+k*step/2
                if at>=duration:break
                pitch=melody[(cycle*8+k)%len(melody)]
                if pitch is not None:note(sig,at,min(step*.95,duration-at),pitch,.11,voice)
    # stereo subtler delay to create space
    sig=np.clip(sig,-1,1);peak=max(.2,np.percentile(abs(sig),99.8));sig*=.77/peak
    delay=int(.013*SR);left=sig; right=np.roll(sig,delay)*.92
    stereo=np.stack([left,right],axis=1)
    raw=p/(name+'.raw');stereo=(np.clip(stereo,-.98,.98)*32767).astype('<i2');raw.write_bytes(stereo.tobytes())
    outfile=folder/(name+'.m4a')
    proc=subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-y','-f','s16le','-ar',str(SR),'-ac','2','-i',str(raw),'-c:a','aac','-b:a','48k','-movflags','+faststart',str(outfile)],capture_output=True)
    raw.unlink()
    if proc.returncode: raise RuntimeError(proc.stderr.decode())
    b64=base64.b64encode(outfile.read_bytes()).decode('ascii')
    # Áudios prontos; não é necessário incluir representação base64 nos recursos
    print(name,'size',outfile.stat().st_size,'sec',duration)

tracks=[
('cinema',{'style':'music','bpm':98,'chords':[[48,55,60,64],[45,52,57,60],[41,48,53,57],[43,50,55,59]],'melody':[72,None,76,74,72,None,67,69],'voice':'bell'},16,'offline'),
('suspense',{'style':'music','bpm':86,'chords':[[45,52,57,60],[46,53,58,61],[43,50,55,58],[40,47,52,55]],'melody':[69,None,None,70,66,None,64,None],'voice':'pad'},16,'offline'),
('lofi',{'style':'music','bpm':82,'chords':[[48,52,55,59],[45,49,52,57],[50,53,57,60],[43,47,50,55]],'melody':[67,72,69,None,64,67,None,71],'voice':'pluck','rhythm':True},16,'offline'),
('afrogroove',{'style':'music','bpm':112,'chords':[[48,52,55],[50,53,57],[53,57,60],[55,59,62]],'melody':[72,74,76,79,76,74,72,None],'voice':'pluck','rhythm':True},16,'offline'),
('piano',{'style':'music','bpm':76,'chords':[[48,55,60,64],[52,59,64,67],[45,52,57,60],[47,54,59,62]],'melody':[76,None,72,74,79,None,76,72],'voice':'bell'},16,'offline'),
('neon',{'style':'music','bpm':126,'chords':[[48,55,60,63],[44,51,56,60],[46,53,58,62],[43,50,55,58]],'melody':[72,75,79,None,77,75,72,67],'voice':'pluck','rhythm':True},16,'offline'),
('whoosh',{'style':'fx','kind':'whoosh','seed':11},2,'offline'),
('impact',{'style':'fx','kind':'impact','seed':16},3,'offline'),
('sino',{'style':'fx','kind':'chime','seed':19},3,'offline'),
('aventura',{'style':'music','bpm':106,'chords':[[48,55,60,64],[50,57,62,65],[45,52,57,60],[43,50,55,59]],'melody':[72,76,79,77,74,72,69,67],'voice':'bell','rhythm':True},20,'online'),
('noite',{'style':'music','bpm':91,'chords':[[45,52,57,60],[41,48,53,56],[43,50,55,59],[40,47,52,55]],'melody':[72,None,69,68,72,74,69,None],'voice':'pad'},20,'online'),
('batalha',{'style':'music','bpm':138,'chords':[[45,52,57,60],[48,55,60,63],[43,50,55,58],[41,48,53,57]],'melody':[69,72,76,74,72,69,67,64],'voice':'pluck','rhythm':True},20,'online'),
('cosmos',{'style':'music','bpm':68,'chords':[[48,55,60,64],[43,50,55,59],[46,53,58,62],[41,48,53,57]],'melody':[79,None,76,None,74,None,72,None],'voice':'pad'},20,'online'),
('amanhecer',{'style':'music','bpm':102,'chords':[[48,55,60,64],[53,60,65,69],[45,52,57,60],[50,57,62,65]],'melody':[72,76,79,81,79,76,74,72],'voice':'bell'},24,'online'),
('drama',{'style':'music','bpm':70,'chords':[[45,52,57,60],[41,48,53,57],[43,50,55,58],[40,47,52,55]],'melody':[69,68,65,64,62,65,68,69],'voice':'pad'},24,'online'),
('romance',{'style':'music','bpm':78,'chords':[[48,55,60,64],[45,52,57,60],[53,60,65,69],[55,62,67,71]],'melody':[76,79,81,79,76,74,72,74],'voice':'bell'},24,'online'),
('comedia',{'style':'music','bpm':122,'chords':[[48,52,55,60],[50,53,57,62],[53,57,60,65],[55,59,62,67]],'melody':[76,79,76,72,79,81,84,79],'voice':'pluck','rhythm':True},20,'online'),
('floresta',{'style':'music','bpm':66,'chords':[[48,55,60,64],[46,53,58,62],[43,50,55,59],[41,48,53,57]],'melody':[79,76,74,76,72,69,72,74],'voice':'pad'},28,'online'),
('revelacao',{'style':'music','bpm':90,'chords':[[48,55,60,67],[45,52,57,64],[41,48,53,60],[43,50,55,62]],'melody':[72,76,79,84,81,79,76,72],'voice':'bell','rhythm':True},24,'online'),
('perseguicao',{'style':'music','bpm':146,'chords':[[45,52,57,60],[46,53,58,62],[43,50,55,58],[41,48,53,57]],'melody':[81,79,76,74,76,79,84,81],'voice':'pluck','rhythm':True},22,'online'),
('afrobeats',{'style':'music','bpm':118,'chords':[[48,52,55,60],[53,57,60,65],[50,53,57,62],[55,59,62,67]],'melody':[72,74,79,76,81,79,74,76],'voice':'pluck','rhythm':True},24,'online'),
('batidanoturna',{'style':'music','bpm':132,'chords':[[45,52,57,60],[48,55,60,63],[46,53,58,62],[43,50,55,58]],'melody':[81,79,76,72,75,79,84,81],'voice':'pluck','rhythm':True},22,'online'),
('loficafe',{'style':'music','bpm':76,'chords':[[48,52,55,59],[45,49,52,57],[50,53,57,60],[43,47,50,55]],'melody':[64,67,71,72,69,67,64,62],'voice':'pluck','rhythm':True},24,'online'),
('nostalgia',{'style':'music','bpm':74,'chords':[[45,52,57,60],[40,47,52,55],[43,50,55,59],[48,55,60,64]],'melody':[72,69,67,64,67,69,72,76],'voice':'bell'},26,'online'),
('vitoria',{'style':'music','bpm':110,'chords':[[48,55,60,64],[53,60,65,69],[55,62,67,71],[48,55,60,67]],'melody':[72,76,79,84,88,84,81,79],'voice':'bell','rhythm':True},24,'online'),
('medo',{'style':'music','bpm':64,'chords':[[45,52,57,60],[46,53,58,61],[44,51,56,59],[43,50,55,58]],'melody':[69,70,69,65,66,65,64,63],'voice':'pad'},26,'online'),
('investigacao',{'style':'music','bpm':88,'chords':[[48,55,60,63],[44,51,56,59],[46,53,58,61],[45,52,57,60]],'melody':[72,75,74,72,70,69,68,70],'voice':'pluck'},24,'online'),
('medieval',{'style':'music','bpm':94,'chords':[[50,57,62,65],[46,53,58,62],[43,50,55,58],[48,55,60,64]],'melody':[74,77,81,84,81,77,74,72],'voice':'bell','rhythm':True},24,'online'),
('ficcaocientifica',{'style':'music','bpm':104,'chords':[[48,55,60,64],[44,51,56,60],[46,53,58,62],[41,48,53,57]],'melody':[84,81,79,76,79,84,86,81],'voice':'pad','rhythm':True},24,'online'),
('pianodelicado',{'style':'music','bpm':72,'chords':[[48,55,60,64],[52,59,64,67],[45,52,57,60],[50,57,62,65]],'melody':[76,72,79,76,74,69,72,76],'voice':'bell'},24,'online'),
('tropical',{'style':'music','bpm':124,'chords':[[53,57,60,65],[55,59,62,67],[50,53,57,62],[48,52,55,60]],'melody':[79,81,84,79,76,74,72,76],'voice':'pluck','rhythm':True},22,'online'),
('resistencia',{'style':'music','bpm':126,'chords':[[45,52,57,60],[48,55,60,64],[43,50,55,59],[41,48,53,57]],'melody':[81,84,81,79,76,74,79,81],'voice':'pluck','rhythm':True},24,'online'),
('cyber',{'style':'music','bpm':140,'chords':[[48,55,60,63],[46,53,58,61],[44,51,56,60],[43,50,55,58]],'melody':[84,81,79,75,79,81,87,84],'voice':'pluck','rhythm':True},22,'online'),
('tensaolenta',{'style':'music','bpm':68,'chords':[[45,52,57,60],[44,51,56,59],[46,53,58,61],[43,50,55,58]],'melody':[69,68,70,69,66,65,64,65],'voice':'pad'},26,'online'),
('diaalegre',{'style':'music','bpm':128,'chords':[[48,52,55,60],[53,57,60,65],[50,53,57,62],[55,59,62,67]],'melody':[72,76,79,84,81,79,76,72],'voice':'pluck','rhythm':True},20,'online'),
('whooshleve',{'style':'fx','kind':'whoosh','seed':41},2,'online'),
('impactograve',{'style':'fx','kind':'impact','seed':45},3,'online'),
('sinoceleste',{'style':'fx','kind':'chime','seed':49},3,'online'),
]
for name,spec,dur,folder in tracks:generate(name,spec,dur,cloud if folder == "online" else p)