import os,strformat,sequtils,strutils

proc readHistorey(path:string,match_rule:string) =
   echo("walk:",path)
   for f in os.walkDirRec(path, {pcFile}):
      if strutils.endsWith(f,match_rule):
        echo getFileInfo(f,true).id

proc main() =
  let argc = paramCount()
  if argc < 2 :
     echo("Usage: ./log_agent <watch_dir:/home/admin/logs> <match_rule:*.nim>")
     quit(0)

  let watch_dir = paramStr(1)
  let match_rule = paramStr(2)
  readHistorey(watch_dir,match_rule)

when isMainModule:
  main()

