package science.transductive.nudge.sensing;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ProsodyFeatureExtractorTest {
    private static short[] sine(double hz,int n,int sr){
        short[] out=new short[n];
        for(int i=0;i<n;i++)out[i]=(short)(12000*Math.sin(2*Math.PI*hz*i/sr));
        return out;
    }

    @Test public void detects440Hz(){
        ProsodyFeatureExtractor.Frame f=ProsodyFeatureExtractor.analyze(sine(440,4096,44100),44100);
        assertEquals(440.0,f.pitchHz,2.0);
        assertTrue(f.periodicity>0.8);
        assertTrue(f.rms>0.1);
    }

    @Test public void boundedInput(){
        ProsodyFeatureExtractor.Frame f=ProsodyFeatureExtractor.analyze(sine(220,10000,44100),44100);
        assertEquals(ProsodyFeatureExtractor.MAX_SAMPLES,f.samples);
    }

    @Test public void rawOnly(){
        assertEquals(4,ProsodyFeatureExtractor.analyze(sine(330,2048,44100),44100).rawFeatures().size());
    }
}
