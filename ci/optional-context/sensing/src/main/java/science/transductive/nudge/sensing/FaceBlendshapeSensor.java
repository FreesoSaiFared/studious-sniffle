package science.transductive.nudge.sensing;

import android.content.Context;
import android.graphics.Bitmap;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.tasks.components.containers.Category;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult;
import java.util.*;

public final class FaceBlendshapeSensor implements AutoCloseable {
    public static final class Frame {
        public final long atElapsedMs,inferenceMs;
        public final int faceCount;
        public final Map<String,Double> blendshapes;
        Frame(long at,long inference,int count,Map<String,Double> shapes){
            atElapsedMs=at;inferenceMs=inference;faceCount=count;
            blendshapes=Collections.unmodifiableMap(new LinkedHashMap<String,Double>(shapes));
        }
    }

    private final FaceLandmarker landmarker;

    public FaceBlendshapeSensor(Context context){
        BaseOptions base=BaseOptions.builder()
            .setModelAssetPath("face_landmarker.task")
            .build();
        FaceLandmarker.FaceLandmarkerOptions options=FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setMinFaceDetectionConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setMinFacePresenceConfidence(0.5f)
            .setNumFaces(1)
            .setOutputFaceBlendshapes(true)
            .setRunningMode(RunningMode.IMAGE)
            .build();
        landmarker=FaceLandmarker.createFromOptions(context,options);
    }

    public Frame detect(Bitmap bitmap){
        if(bitmap==null)throw new IllegalArgumentException("bitmap");
        long start=android.os.SystemClock.elapsedRealtime();
        FaceLandmarkerResult result=landmarker.detect(new BitmapImageBuilder(bitmap).build());
        long end=android.os.SystemClock.elapsedRealtime();
        LinkedHashMap<String,Double> raw=new LinkedHashMap<String,Double>();
        if(result.faceBlendshapes().isPresent()&&!result.faceBlendshapes().get().isEmpty()){
            for(Category c:result.faceBlendshapes().get().get(0)){
                String name=c.categoryName();
                double score=c.score();
                if(name!=null&&!name.isEmpty()&&Double.isFinite(score))raw.put(name,score);
            }
        }
        return new Frame(end,end-start,result.faceLandmarks().size(),raw);
    }

    @Override public void close(){landmarker.close();}
}
