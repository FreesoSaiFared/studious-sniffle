package science.transductive.nudge.sensing;

import java.util.*;

/**
 * Raw, bounded prosody features. Pitch extraction is adapted from the MIT-licensed
 * YIN implementation in thestbar/tunify@836876a1b30581ad99495bfde74b4060df06f17d.
 * It emits signal measurements only; it does not infer emotion, deception, diagnosis or intent.
 */
public final class ProsodyFeatureExtractor {
    public static final int MAX_SAMPLES=4096;
    private ProsodyFeatureExtractor(){}

    public static final class Frame {
        public final int sampleRate,samples;
        public final double rms,zeroCrossingRate,pitchHz,periodicity;
        Frame(int sr,int n,double rms,double zcr,double pitch,double periodicity){
            sampleRate=sr;samples=n;this.rms=rms;zeroCrossingRate=zcr;pitchHz=pitch;this.periodicity=periodicity;
        }
        public Map<String,Double> rawFeatures(){
            LinkedHashMap<String,Double> m=new LinkedHashMap<String,Double>();
            m.put("rms",rms);m.put("zero_crossing_rate",zeroCrossingRate);
            m.put("pitch_hz",pitchHz);m.put("periodicity",periodicity);
            return Collections.unmodifiableMap(m);
        }
    }

    public static Frame analyze(short[] input,int sampleRate){
        if(input==null||input.length<256)throw new IllegalArgumentException("need >=256 samples");
        if(sampleRate<8000||sampleRate>192000)throw new IllegalArgumentException("sampleRate");
        int n=Math.min(input.length,MAX_SAMPLES);
        short[] x=Arrays.copyOf(input,n);
        double sq=0;int zc=0;
        for(int i=0;i<n;i++){
            double s=x[i]/32768.0;sq+=s*s;
            if(i>0&&((x[i-1]<0&&x[i]>=0)||(x[i-1]>=0&&x[i]<0)))zc++;
        }
        double rms=Math.sqrt(sq/n),zcr=zc/(double)Math.max(1,n-1);
        YinResult y=yin(x,sampleRate);
        return new Frame(sampleRate,n,rms,zcr,y.pitchHz,y.periodicity);
    }

    private static final class YinResult {
        final double pitchHz,periodicity;
        YinResult(double p,double c){pitchHz=p;periodicity=c;}
    }

    private static YinResult yin(short[] input,int sampleRate){
        int half=input.length/2;
        double[] y=new double[half];
        for(int tau=1;tau<half;tau++){
            double sum=0;
            for(int j=0;j<half;j++){
                double d=input[j]-(double)input[j+tau];
                sum+=d*d;
            }
            y[tau]=sum;
        }
        y[0]=1.0;
        double running=0;
        for(int tau=1;tau<half;tau++){
            running+=y[tau];
            y[tau]=running==0?1.0:y[tau]*tau/running;
        }
        int tau=-1;
        for(int t=2;t<half;t++){
            if(y[t]<0.15){
                while(t+1<half&&y[t+1]<y[t])t++;
                tau=t;break;
            }
        }
        if(tau<0)return new YinResult(-1.0,0.0);
        int x0=tau<1?tau:tau-1,x2=tau+1<half?tau+1:tau;
        double better=tau;
        if(x0==tau)better=y[tau]<=y[x2]?tau:x2;
        else if(x2==tau)better=y[tau]<=y[x0]?tau:x0;
        else{
            double s0=y[x0],s1=y[tau],s2=y[x2],den=2.0*s1-s2-s0;
            if(Math.abs(den)>1e-12)better=tau+0.5*(s2-s0)/den;
        }
        if(!Double.isFinite(better)||better<=0)return new YinResult(-1.0,0.0);
        return new YinResult(sampleRate/better,Math.max(0.0,Math.min(1.0,1.0-y[tau])));
    }
}
