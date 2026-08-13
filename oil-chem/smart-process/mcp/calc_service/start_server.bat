@echo off
rem ============================================================
rem  calc_service standalone service (Flask :5081)
rem  - Code dir : %~dp0calc_service\backend  (refactored solver; coexists
rem               with solve/ on 5080)
rem  - Storage  : PostgreSQL (schema solve_db)，见 backend/config.py
rem               DATABASE_URL（默认 postgresql://huilian:huilian2026
rem               @localhost:5432/huilian）。运行时不再读 Excel。
rem  - Runtime  : local Python in PATH
rem               (需 flask/flask-cors/ortools/pandas/sqlalchemy/psycopg2)
rem  - Port     : 5081  (isolated from solve/ 5080)
rem               SOLVER_PORT env var is read by calc_service.backend.app
rem  - Run as module (python -m calc_service.backend.app) so package-relative
rem    imports resolve; must be launched from repo root.
rem
rem    First-time setup (本机 Python 已装齐则无需执行):
rem      python -m pip install flask flask-cors ortools pandas sqlalchemy psycopg2-binary
rem    首次部署需先把 Excel 历史数据迁入 solve_db（仅一次）:
rem      python -m calc_service.backend.migrate_excel_to_db
rem  - NOTE: do NOT use solve\start_server.bat, its
rem          "taskkill /IM python.exe" kills ALL python
rem          processes including the Huilian backend.
rem ============================================================

set REPO_DIR=%~dp0..
set SOLVER_PY=python
set SOLVER_PORT=5081

where %SOLVER_PY% >nul 2>nul
if errorlevel 1 (
  echo [ERROR] python not found in PATH.
  echo         install Python or fix PATH, then retry.
  pause & exit /b 1
)
if not exist "%~dp0backend\app.py" (
  echo [ERROR] calc_service backend code dir not found: %~dp0backend
  pause & exit /b 1
)

echo Starting calc_service service on port %SOLVER_PORT% ...
start "Huilian-Solver-v1" cmd /k "cd /d %REPO_DIR% && set SOLVER_PORT=%SOLVER_PORT% && %SOLVER_PY% -m calc_service.backend.app"
echo calc_service: http://localhost:%SOLVER_PORT%
