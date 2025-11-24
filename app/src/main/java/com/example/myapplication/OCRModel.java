package com.example.myapplication;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.support.common.FileUtil;


public class OCRModel  {
    private Interpreter interpreter;
    private final char[] alphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
    private final int VOCAB_SIZE = 63;

    public OCRModel(Context context) {
        try {
            interpreter = new Interpreter(
                    FileUtil.loadMappedFile(context, "model.tflite"),
                    new Interpreter.Options().setUseXNNPACK(false)
            );
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public String runInference(Bitmap bitmap) {

        float[][][][] input = preprocess(bitmap);

        // Output shape = [1, timeSteps, vocab]
        float[][][] output = new float[1][28][VOCAB_SIZE];

        interpreter.run(input, output);

        return decodeCtc(output[0]);
    }

    private float[][][][] preprocess(Bitmap bitmap) {

        Bitmap resized = Bitmap.createScaledBitmap(bitmap, 128, 128, true);

        float[][][][] input = new float[1][128][128][3];

        for (int y = 0; y < 128; y++) {
            for (int x = 0; x < 128; x++) {
                int pixel = resized.getPixel(x, y);

                input[0][y][x][0] = Color.red(pixel) / 255.0f;
                input[0][y][x][1] = Color.green(pixel) / 255.0f;
                input[0][y][x][2] = Color.blue(pixel) / 255.0f;
            }
        }

        return input;
    }

    private String decodeCtc(float[][] timeSteps) {
        StringBuilder sb = new StringBuilder();
        int lastIdx = -1;

        for (float[] t : timeSteps) {
            int maxIdx = 0;
            float maxVal = -Float.MAX_VALUE;

            for (int i = 0; i < VOCAB_SIZE; i++) {
                if (t[i] > maxVal) {
                    maxVal = t[i];
                    maxIdx = i;
                }
            }

            if (maxIdx != lastIdx && maxIdx < alphabet.length) {
                sb.append(alphabet[maxIdx]);
            }

            lastIdx = maxIdx;
        }

        return sb.toString();
    }
}