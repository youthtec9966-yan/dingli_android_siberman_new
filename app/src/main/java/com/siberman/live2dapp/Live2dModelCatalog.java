package com.siberman.live2dapp;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class Live2dModelCatalog {
    public static final String DEFAULT_MODEL_PATH = "/models/police1124/%E8%AD%A6%E5%AF%9F%E6%8B%86.model3.json";
    private static final String DEFAULT_MODEL_LABEL = "police1124";
    private static final String DEFAULT_MODEL_PREVIEW = "models/police1124/警察拆.4096/texture_00.png";

    private static final String MODELS_ROOT = "models/live2d_models";

    private Live2dModelCatalog() {
    }

    public static List<ModelEntry> load(Context context) {
        List<ModelEntry> items = new ArrayList<>();
        items.add(buildFallbackEntry());
        if (context == null) {
            return items;
        }

        AssetManager assetManager = context.getAssets();
        try {
            String[] directories = assetManager.list(MODELS_ROOT);
            if (directories != null) {
                for (String directory : directories) {
                    if (directory == null || directory.trim().isEmpty()) {
                        continue;
                    }
                    String modelDir = MODELS_ROOT + "/" + directory;
                    String modelFile = findModelJson(assetManager, modelDir);
                    if (modelFile == null) {
                        continue;
                    }
                    String modelPath = "/" + modelDir + "/" + modelFile;
                    if (DEFAULT_MODEL_PATH.equalsIgnoreCase(modelPath)) {
                        continue;
                    }
                    items.add(new ModelEntry(
                            directory,
                            modelPath,
                            findPreviewImage(assetManager, modelDir, 3)
                    ));
                }
            }
        } catch (IOException ignored) {
        }

        if (items.size() > 1) {
            Collections.sort(items.subList(1, items.size()), Comparator.comparing(entry -> entry.label.toLowerCase()));
        }
        return items;
    }

    public static String resolveModelPath(Context context, String savedPath) {
        String normalizedSavedPath = normalize(savedPath);
        List<ModelEntry> items = load(context);
        for (ModelEntry item : items) {
            if (item.path.equalsIgnoreCase(normalizedSavedPath)) {
                return item.path;
            }
        }
        if (!normalizedSavedPath.isEmpty()) {
            return normalizedSavedPath;
        }
        return DEFAULT_MODEL_PATH;
    }

    private static String findModelJson(AssetManager assetManager, String modelDir) throws IOException {
        String[] names = assetManager.list(modelDir);
        if (names == null) {
            return null;
        }
        String preferred = null;
        for (String name : names) {
            if (name == null || !name.endsWith(".model3.json")) {
                continue;
            }
            if (preferred == null || name.equalsIgnoreCase(lastSegment(modelDir) + ".model3.json")) {
                preferred = name;
            }
        }
        return preferred;
    }

    private static String lastSegment(String value) {
        int index = value.lastIndexOf('/');
        return index >= 0 ? value.substring(index + 1) : value;
    }

    private static String findPreviewImage(AssetManager assetManager, String directory, int depth) throws IOException {
        String[] names = assetManager.list(directory);
        if (names == null) {
            return null;
        }
        String fallback = null;
        for (String name : names) {
            if (name == null || name.trim().isEmpty()) {
                continue;
            }
            String childPath = directory + "/" + name;
            String[] nested = assetManager.list(childPath);
            if (nested != null && nested.length > 0 && depth > 0) {
                String nestedMatch = findPreviewImage(assetManager, childPath, depth - 1);
                if (nestedMatch != null) {
                    return nestedMatch;
                }
                continue;
            }
            if (!isImageFile(name)) {
                continue;
            }
            if (fallback == null) {
                fallback = childPath;
            }
            if (name.equalsIgnoreCase("texture_00.png")) {
                return childPath;
            }
        }
        return fallback;
    }

    private static boolean isImageFile(String fileName) {
        String lowerCase = fileName.toLowerCase();
        return lowerCase.endsWith(".png")
                || lowerCase.endsWith(".jpg")
                || lowerCase.endsWith(".jpeg")
                || lowerCase.endsWith(".webp");
    }

    private static ModelEntry buildFallbackEntry() {
        return new ModelEntry(DEFAULT_MODEL_LABEL, DEFAULT_MODEL_PATH, DEFAULT_MODEL_PREVIEW);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    public static final class ModelEntry {
        public final String label;
        public final String path;
        public final String previewAssetPath;

        public ModelEntry(String label, String path, String previewAssetPath) {
            this.label = label;
            this.path = path;
            this.previewAssetPath = previewAssetPath;
        }
    }
}
