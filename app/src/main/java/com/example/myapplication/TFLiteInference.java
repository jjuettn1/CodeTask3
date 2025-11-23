package com.example.myapplication;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.util.Log;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.support.common.ops.NormalizeOp;
import org.tensorflow.lite.support.image.ImageProcessor;
import org.tensorflow.lite.support.image.TensorImage;
import org.tensorflow.lite.support.image.ops.ResizeOp;
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TFLiteInference {

    public static final String TAG = "TFLiteInference";
    private static final String MODEL_FILE_NAME = "model.tflite";

    private static final int TIME_STEPS = 64;
    private static final int VOCAB_SIZE = 63;

    private static final Vocabulary vocab = new Vocabulary();

    private static MappedByteBuffer loadModelFile(Context context) throws IOException {
        AssetFileDescriptor fileDescriptor = context.getAssets().openFd(MODEL_FILE_NAME);
        try (FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor())) {
            FileChannel fileChannel = inputStream.getChannel();
            long startOffset = fileDescriptor.getStartOffset();
            long declaredLength = fileDescriptor.getDeclaredLength();
            return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
        }
    }

    public static ByteBuffer preprocessBitmap(Bitmap inputBitmap) {
        final int INPUT_WIDTH = 128;
        final int INPUT_HEIGHT = 128;

        final float NORMALIZE_MEAN = 0.0f;
        final float NORMALIZE_STD = 255.0f;

        TensorImage tensorImage = new TensorImage(DataType.FLOAT32);
        tensorImage.load(inputBitmap);

        ImageProcessor imageProcessor = new ImageProcessor.Builder()
                .add(new ResizeOp(INPUT_HEIGHT, INPUT_WIDTH, ResizeOp.ResizeMethod.BILINEAR))
                .add(new NormalizeOp(NORMALIZE_MEAN, NORMALIZE_STD))
                .build();

        TensorImage processedImage = imageProcessor.process(tensorImage);
        return processedImage.getBuffer();
    }

    public static String recognizeWord(Context context, Bitmap inputBitmap) {
        ByteBuffer byteBuffer = preprocessBitmap(inputBitmap);

        Interpreter tflite = null;
        String decodedWord = "INFERENCE_FAILED";

        try {
            MappedByteBuffer tfliteModel = loadModelFile(context);
            tflite = new Interpreter(tfliteModel, new Interpreter.Options());

            TensorBuffer inputFeature0 = TensorBuffer.createFixedSize(
                    new int[]{1, 128, 128, 3},
                    DataType.FLOAT32
            );
            inputFeature0.loadBuffer(byteBuffer);

            float[][][] rawOutput3D = new float[1][TIME_STEPS][VOCAB_SIZE];

            tflite.run(inputFeature0.getBuffer(), rawOutput3D);

            float[] flattenedOutput = new float[TIME_STEPS * VOCAB_SIZE];
            int index = 0;
            for (int t = 0; t < TIME_STEPS; t++) {
                for (int v = 0; v < VOCAB_SIZE; v++) {
                    flattenedOutput[index++] = rawOutput3D[0][t][v];
                }
            }

            decodedWord = decodePrediction(flattenedOutput, vocab);

        } catch (IOException e) {
            Log.e(TAG, "Error running inference or loading model", e);
        } finally {
            if (tflite != null) {
                tflite.close();
            }
        }
        return decodedWord;
    }

    public static String decodePrediction(float[] rawOutput, Vocabulary vocab) {
        final int timeSteps = rawOutput.length / vocab.vocabSize;

        int[] predIndices = new int[timeSteps];

        for (int t = 0; t < timeSteps; t++) {
            int bestIndex = 0;
            float maxProb = -1.0f;

            for (int v = 0; v < vocab.vocabSize; v++) {
                float currentProb = rawOutput[(t * vocab.vocabSize) + v];

                if (currentProb > maxProb) {
                    maxProb = currentProb;
                    bestIndex = v;
                }
            }
            predIndices[t] = bestIndex;
        }

        List<Integer> decodedIndices = new ArrayList<>();
        Integer prevIdx = null;

        for (int idx : predIndices) {
            boolean isDuplicate = (prevIdx != null) && (idx == prevIdx);
            boolean isBlank = (idx == vocab.blankIndex);

            if (!isDuplicate && !isBlank) {
                decodedIndices.add(idx);
            }
            prevIdx = idx;
        }

        StringBuilder decodedWord = new StringBuilder();
        for (int idx : decodedIndices) {
            if (vocab.numToChar.containsKey(idx)) {
                decodedWord.append(vocab.numToChar.get(idx));
            }
        }

        return decodedWord.toString();
    }
}
class Vocabulary {
    private static final String ALPHABET_CHARS =
            "0123456789" + "abcdefghijklmnopqrstuvwxyz" + "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    public final Map<Character, Integer> charToNum;
    public final Map<Integer, Character> numToChar;
    public final int vocabSize;
    public final int blankIndex;

    public Vocabulary() {
        charToNum = new HashMap<>();
        numToChar = new HashMap<>();

        for (int i = 0; i < ALPHABET_CHARS.length(); i++) {
            char c = ALPHABET_CHARS.charAt(i);
            charToNum.put(c, i);
            numToChar.put(i, c);
        }

        vocabSize = ALPHABET_CHARS.length() + 1;
        blankIndex = vocabSize - 1; // Index 62
    }
}