package com.siberman.live2dapp;

import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.siberman.live2dapp.config.AliyunConfigStore;

public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        AliyunConfigStore store = new AliyunConfigStore(this);
        Class<?> next = store.hasCompletedOnboarding() ? PlayerActivity.class : SettingsActivity.class;
        startActivity(new Intent(this, next));
        finish();
    }
}
