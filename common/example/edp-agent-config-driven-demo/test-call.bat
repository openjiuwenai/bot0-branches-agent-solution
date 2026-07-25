@echo off
REM ================================================================
REM test-call.bat - Test the smart customer service agent
REM ================================================================

set BASE_URL=http://localhost:8190

echo ================================================================
echo  Test 1: Basic greeting
echo ================================================================
curl -s -X POST "%BASE_URL%/v1/query" ^
  -H "Content-Type: application/json" ^
  -d "{\"conversation_id\":\"test-001\",\"message\":\"你好\"}"
echo.
echo.

echo ================================================================
echo  Test 2: Product consultation
echo ================================================================
curl -s -X POST "%BASE_URL%/v1/query" ^
  -H "Content-Type: application/json" ^
  -d "{\"conversation_id\":\"test-002\",\"message\":\"你们有哪些产品套餐？\"}"
echo.
echo.

echo ================================================================
echo  Test 3: Business inquiry
echo ================================================================
curl -s -X POST "%BASE_URL%/v1/query" ^
  -H "Content-Type: application/json" ^
  -d "{\"conversation_id\":\"test-003\",\"message\":\"如何查询我的账单？\"}"
echo.
echo.

echo ================================================================
echo  Test 4: Out of scope
echo ================================================================
curl -s -X POST "%BASE_URL%/v1/query" ^
  -H "Content-Type: application/json" ^
  -d "{\"conversation_id\":\"test-004\",\"message\":\"帮我推荐一只股票\"}"
echo.
echo.

echo ================================================================
echo  Test 5: A2A JSON-RPC (streaming)
echo ================================================================
curl -s -N -X POST "%BASE_URL%/a2a" ^
  -H "Content-Type: application/json" ^
  -H "Accept: text/event-stream" ^
  -d "{\"jsonrpc\":\"2.0\",\"method\":\"SendStreamingMessage\",\"id\":\"req-5\",\"params\":{\"message\":{\"role\":\"user\",\"parts\":[{\"text\":\"我想投诉服务态度问题\"}],\"messageId\":\"msg-5\",\"contextId\":\"test-005\"}}}"
echo.

pause
