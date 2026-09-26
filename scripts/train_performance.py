#!/usr/bin/env python3
"""Train an EXPERIMENTAL policy advisor using real, session-separated labeled data.
No sample/synthetic weights are bundled. Does not establish a performance gain.
Usage: train_performance.py TRAIN.csv VALIDATION.csv OUTPUT.tflite
"""
import argparse
import csv
from pathlib import Path
import numpy as np
import tensorflow as tf

COLUMNS = ("frame_ms", "hand_ms", "thermal", "battery", "current_scale",
           "target_scale", "target_hand_interval_ms")


def load(path):
    with open(path, newline="", encoding="utf-8") as source:
        reader = csv.DictReader(source)
        if not set(COLUMNS) <= set(reader.fieldnames or []):
            raise ValueError("CSV columns required: " + ",".join(COLUMNS))
        data = np.asarray([[float(row[c]) for c in COLUMNS] for row in reader], dtype=np.float32)
    if data.ndim != 2 or len(data) < 100 or not np.isfinite(data).all():
        raise ValueError("Need >=100 valid measured and labeled samples per split")
    if not ((data[:, 2] >= 0) & (data[:, 2] <= 6)).all():
        raise ValueError("thermal must use Android status 0..6")
    if not ((data[:, 3] >= 0) & (data[:, 3] <= 1)).all():
        raise ValueError("battery must be a fraction 0..1")
    if not ((data[:, 4:6] >= .6) & (data[:, 4:6] <= 1)).all():
        raise ValueError("current/target scale must be .6..1")
    if not ((data[:, 6] >= 16) & (data[:, 6] <= 100)).all():
        raise ValueError("hand interval must be 16..100ms")
    return data[:, :5] / np.array([33.3, 50, 6, 1, 1], np.float32), data[:, 5:] / [1., 100.]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("train")
    parser.add_argument("validation")
    parser.add_argument("output")
    args = parser.parse_args()
    if Path(args.train).resolve() == Path(args.validation).resolve():
        parser.error("Validation must be independent (different devices/sessions)")
    tf.keras.utils.set_random_seed(42)
    x, y = load(args.train)
    vx, vy = load(args.validation)
    model = tf.keras.Sequential([
        tf.keras.layers.Input(shape=(5,)),
        tf.keras.layers.Dense(16, activation="relu"),
        tf.keras.layers.Dense(8, activation="relu"),
        tf.keras.layers.Dense(2, activation="sigmoid"),
    ])
    model.compile(optimizer=tf.keras.optimizers.Adam(0.001), loss="mse", metrics=["mae"])
    model.fit(x, y, validation_data=(vx, vy), epochs=100, batch_size=64,
              callbacks=[tf.keras.callbacks.EarlyStopping(patience=10, restore_best_weights=True)])
    print("Held-out error (NOT FPS improvement):", model.evaluate(vx, vy, verbose=0))
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    output = converter.convert()
    check = tf.lite.Interpreter(model_content=output)
    check.allocate_tensors()
    assert tuple(check.get_input_details()[0]["shape"]) == (1, 5)
    assert tuple(check.get_output_details()[0]["shape"]) == (1, 2)
    Path(args.output).write_bytes(output)
    print("Experimental model exported:", args.output, len(output), "bytes")


if __name__ == "__main__":
    main()
