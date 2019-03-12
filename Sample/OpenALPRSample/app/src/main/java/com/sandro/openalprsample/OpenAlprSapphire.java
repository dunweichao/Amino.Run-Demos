package com.sandro.openalprsample;

import android.os.AsyncTask;

import org.openalpr.SapphireAccess;
import org.openalpr.Results;

import org.openalpr.Configuration;
/**
 * Created by SMoon on 2/27/2018.
 */

public class OpenAlprSapphire extends AsyncTask<Void, Void, Results> {
    private String ANDROID_DATA_DIR;
    private String countryCode;
    private String region;
    private String imageFilePath;
    private Configuration.ProcessEntity processEntity;
    SapphireAccess sa;

    public OpenAlprSapphire(String ANDROID_DATA_DIR, String countryCode, String region, String imageFilePath, Configuration.ProcessEntity processEntity, SapphireAccess sa) {
        this.sa = sa;
        this.ANDROID_DATA_DIR = ANDROID_DATA_DIR;
        this.countryCode = countryCode;
        this.region = region;
        this.imageFilePath = imageFilePath;
        this.processEntity = processEntity;
    }

    @Override
    protected Results doInBackground(Void... params) {
        Results results = sa.getResult(ANDROID_DATA_DIR, this.countryCode, this.region, this.imageFilePath, processEntity);
        return results;
    }
}
