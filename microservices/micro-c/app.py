from flask import Flask, jsonify
import time, os

app = Flask(__name__)

@app.get("/")
def home():
    delay = float(os.getenv("DELAY_SECONDS", "0"))
    if delay:
        time.sleep(delay)
    return jsonify(service="micro-c", message="Hello from Micro C", delay=delay)

@app.get("/health")
def health():
    return jsonify(status="ok", service="micro-c")

app.run(host="0.0.0.0", port=8443)
