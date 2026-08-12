@echo off
cd /d D:\Code\liwanxing-learning-projects
del do-clean.bat
git add -A
git commit -m "remove temp bat"
git push origin main
