package com.example.leetcode.service.runner;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

@Component
public class DockerProcessRunner {
    private final ObjectMapper mapper=new ObjectMapper(); private final int timeout;
    public DockerProcessRunner(@Value("${leetcode.runner.timeout-seconds:5}") int timeout){this.timeout=timeout;}
    public ExecutionResult executeInDocker(String image,String commandArgs,String input){
        String name="leetcode-run-"+UUID.randomUUID(); List<String> cmd=new ArrayList<>(List.of("docker","run","--rm","--name",name,"-i","--network","none","--cpus","0.5","--memory","128m","--pids-limit","64","--read-only","--tmpfs","/tmp:rw,noexec,nosuid,size=16m","--cap-drop","ALL","--security-opt","no-new-privileges",image));
        cmd.addAll(Arrays.asList(commandArgs.trim().split("\\s+"))); long started=System.nanoTime();
        try{Process process=new ProcessBuilder(cmd).start();ExecutorService io=Executors.newFixedThreadPool(2);Future<String> out=io.submit(()->read(process.getInputStream()));Future<String> err=io.submit(()->read(process.getErrorStream()));
            try(OutputStream os=process.getOutputStream()){os.write(input.getBytes(StandardCharsets.UTF_8));}
            if(!process.waitFor(timeout,TimeUnit.SECONDS)){new ProcessBuilder("docker","rm","-f",name).start().waitFor(2,TimeUnit.SECONDS);process.destroyForcibly();io.shutdownNow();return new ExecutionResult("TIME_LIMIT_EXCEEDED",0,0,timeout*1000,"Execution timed out");}
            String stdout=out.get(1,TimeUnit.SECONDS),stderr=err.get(1,TimeUnit.SECONDS);io.shutdownNow();if(process.exitValue()!=0)return new ExecutionResult(stderr.contains("COMPILE_ERROR")?"COMPILE_ERROR":"RUNTIME_ERROR",0,0,elapsed(started),bounded(stderr));return parse(stdout,stderr,started);
        }catch(Exception e){try{new ProcessBuilder("docker","rm","-f",name).start().waitFor(2,TimeUnit.SECONDS);}catch(Exception ignored){}return new ExecutionResult("SYSTEM_ERROR",0,0,elapsed(started),bounded(e.getMessage()));}
    }
    private String read(InputStream in)throws IOException{byte[] bytes=in.readNBytes(65537);if(bytes.length>65536)throw new IOException("Output limit exceeded");return new String(bytes,StandardCharsets.UTF_8).trim();}
    private ExecutionResult parse(String stdout,String stderr,long started){try{int at=stdout.indexOf('[');if(at<0)return new ExecutionResult("RUNTIME_ERROR",0,0,elapsed(started),"No result payload");JsonNode a=mapper.readTree(stdout.substring(at));int passed=0;String error="";for(JsonNode r:a){if(r.path("passed").asBoolean())passed++;else if(r.has("error"))error=r.get("error").asText();else if(error.isEmpty())error="Output did not match expected result";}return new ExecutionResult(passed==a.size()&&a.size()>0?"ACCEPTED":"WRONG_ANSWER",passed,a.size(),elapsed(started),bounded(error));}catch(Exception e){return new ExecutionResult("RUNTIME_ERROR",0,0,elapsed(started),"Invalid runner output: "+bounded(e.getMessage()));}}
    private int elapsed(long start){return (int)TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);}private String bounded(String v){if(v==null)return null;return v.substring(0,Math.min(v.length(),4000));}
}
