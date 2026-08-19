import os
import multiprocessing

bind = "0.0.0.0:4398"
workers = int(os.environ.get("GUNICORN_WORKERS", multiprocessing.cpu_count() * 2 + 1))
worker_class = "sync"
timeout = 120
keepalive = 5
graceful_timeout = 30
max_requests = 1000
max_requests_jitter = 50
accesslog = "-"
errorlog = "-"
loglevel = "info"
proc_name = "dfw-ragwheel"
