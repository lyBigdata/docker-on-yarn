package com.sogou.dockeronyarn.appmaster.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.DockerException;
import com.github.dockerjava.api.NotModifiedException;
import com.github.dockerjava.api.command.*;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.DockerClientConfig;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.yarn.webapp.NotFoundException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class YarnDockerClient {


  private static final Log LOG = LogFactory.getLog(YarnDockerClient.class);
  private static String CONTAINER_RUNNER_SCRIPT_PATH = "/runner.py";

  private static String[] RUN_CMD = new String[]{"/usr/bin/python", CONTAINER_RUNNER_SCRIPT_PATH};
  private final YarnDockerClientParam yarnDockerClientParam;

  private long streamTimeout = 10 * 1000;
  private int stopTimeout = 60;

  private Thread stdoutThread;

  private Thread stderrThread;

  private WaitTaskRunner wtr;

  private Thread waitThread;

  private String containerId;

  private volatile boolean containerStoped = false;

  private DockerClient docker;

  private Process pullProcess = null;

  private int pullProcessTryNum = 3;

  private String runPath;

  private List<Bind> volumeBinds = new ArrayList<Bind>();


  public YarnDockerClient(YarnDockerClientParam yarnDockerClientParam) {
    this.yarnDockerClientParam = yarnDockerClientParam;
  }

  /**
   * Start container, non block.
   *
   * @return ExitCode
   */
  public int startContainer() throws IOException, DockerException {

    DockerClient dockerClient = getDockerClient();
    this.docker = dockerClient;

    LOG.info("Pulling docker image: " + yarnDockerClientParam.dockerImage);
    try {
      docker.pullImageCmd(yarnDockerClientParam.dockerImage).exec().close();
    }catch (IOException e){
      throw new RuntimeException("Pull docker image failed.", e);
    }

    CreateContainerCmd createContainerCmd = getCreateContainerCmd();
    LOG.info("Creating docker container: " + createContainerCmd.toString());
    this.containerId = createContainerCmd.exec().getId();

    LOG.info("Start docker container: " + containerId);
    getStartContainerCmd().exec();

    startLogTaillingThreads(containerId);

    this.wtr = new WaitTaskRunner(docker, containerId);
    this.waitThread = new Thread(wtr, "waitThread");

    waitThread.start();
    return 0;
  }

  /**
   * Block until the docker container exit
   *
   * @return ExitValue
   */

  public int waitContainerExit() {
    int value;
    try {
      waitThread.join(this.yarnDockerClientParam.clientTimeout);
    } catch (InterruptedException e) {
      System.out.println("container  interrupted");
      e.printStackTrace();
    }

    value = wtr.getExitCode();



    return value;
  }

  public int runtask() throws IOException, DockerException {
    int exitValue = startContainer();
    if (exitValue != 0)
      return exitValue;

    exitValue = waitContainerExit();

    finish();

    return exitValue;
  }

  private DockerClient getDockerClient() {
    LOG.info("Initializing Docker Client");
    DockerClientConfig.DockerClientConfigBuilder configBuilder = DockerClientConfig
            .createDefaultConfigBuilder();
    configBuilder.withLoggingFilter(this.yarnDockerClientParam.debugFlag)
            .withUri("https://" + yarnDockerClientParam.dockerHost)
            .withDockerCertPath(yarnDockerClientParam.dockerCertPath);
    DockerClientConfig config = configBuilder.build();

    return DockerClientBuilder.getInstance(config)
            .build();
  }

  private CreateContainerCmd getCreateContainerCmd() {
    ArrayList<String> cmds = new ArrayList<String>();
    Collections.addAll(cmds, RUN_CMD);
    Collections.addAll(cmds, yarnDockerClientParam.cmdAndArgs);
    yarnDockerClientParam.cmdAndArgs = cmds.toArray(yarnDockerClientParam.cmdAndArgs);


    CreateContainerCmd con = docker.createContainerCmd(this.yarnDockerClientParam.dockerImage);
    con.withCpuShares(this.yarnDockerClientParam.containerVirtualCores);
    con.withMemoryLimit(new Long(this.yarnDockerClientParam.containerMemory * 1024 * 1024));
    con.withAttachStderr(true);
    con.withAttachStdin(true);
    con.withAttachStdout(true);
    con.withCmd(this.yarnDockerClientParam.cmdAndArgs);
    return con;
  }

  private StartContainerCmd getStartContainerCmd() {
    StartContainerCmd startCmd = docker.startContainerCmd(containerId);

    this.volumeBinds.add(new Bind(yarnDockerClientParam.runnerScriptPath,
            new Volume(CONTAINER_RUNNER_SCRIPT_PATH), AccessMode.ro));

    startCmd.withBinds(volumeBinds.toArray(new Bind[0]));
    return startCmd;
  }

  public void finish() {
    LOG.info("Finishing");

    ensureContainerRemoved();
    try {
      stderrThread.join(this.streamTimeout);
    } catch (InterruptedException e) {
      e.printStackTrace();
    }

    try {
      stdoutThread.join(this.streamTimeout);
    } catch (InterruptedException e) {
      e.printStackTrace();
    }

    try {
      this.docker.close();
    } catch (IOException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
  }

  private void startLogTaillingThreads(final String containerId) {
    this.stdoutThread = new Thread() {
      @Override
      public void run() {
        BufferedReader reader = null;
        try {
          LogContainerCmd logCmd = docker.logContainerCmd(containerId);
          logCmd.withFollowStream(true);
          logCmd.withStdErr(false);
          logCmd.withStdOut(true);
          logCmd.withTimestamps(false);

          InputStream input = logCmd.exec();
          reader = new BufferedReader(new InputStreamReader(input));
          String line;
          while ((line = reader.readLine()) != null && !isInterrupted()) {
            System.out.println(line.trim());
          }

        } catch (Exception e) {
          e.printStackTrace();
        } finally {
          if (reader != null) {
            try {
              reader.close();
              LOG.info("stdout closed");
            } catch (IOException e) {
              e.printStackTrace();
            }
          }

        }
      }
    };

    this.stderrThread = new Thread() {

      @Override
      public void run() {
        BufferedReader reader = null;
        try {
          LogContainerCmd logCmd = docker.logContainerCmd(containerId);
          logCmd.withFollowStream(true);
          logCmd.withStdErr(true);
          logCmd.withStdOut(false);
          logCmd.withTimestamps(false);
          InputStream input = logCmd.exec();
          reader = new BufferedReader(new InputStreamReader(input));
          String line;
          while ((line = reader.readLine()) != null && !isInterrupted()) {

            System.err.println(line.trim());

          }

        } catch (Exception e) {
          e.printStackTrace();
        } finally {
          if (reader != null) {
            try {
              reader.close();
              LOG.info("stderr closed");
            } catch (IOException e) {
              e.printStackTrace();
            }
          }

        }
      }
    };

    try {
      stdoutThread.start();
    } catch (IllegalThreadStateException e) {
    }

    try {
      stderrThread.start();
    } catch (IllegalThreadStateException e) {
    }
  }

  public static void main(String[] args) {

    int result = -1;
    try {
      YarnDockerClientParam yarnDockerClientParam = new YarnDockerClientParam();
      try {
        yarnDockerClientParam.initFromCmdlineArgs(args);
        if (yarnDockerClientParam.isPrintHelp) {
          yarnDockerClientParam.printUsage();
          System.exit(ExitCode.SUCC.getValue());
        }
      } catch (IllegalArgumentException e) {
        System.err.println(e.getLocalizedMessage());
        yarnDockerClientParam.printUsage();
        System.exit(ExitCode.ILLEGAL_ARGUMENT.getValue());
      }

      YarnDockerClient client = new YarnDockerClient(yarnDockerClientParam);
      Runtime.getRuntime().addShutdownHook(new Thread(client.new ShutdownHook(), "shutdownWork"));

      result = client.runtask();

    } catch (Throwable t) {
      LOG.fatal("Error running CLient", t);
      System.exit(ExitCode.FAIL.getValue());
    }

    if (result == 0) {
      LOG.info("docker task completed successfully");
      System.exit(ExitCode.SUCC.getValue());
    }

    LOG.info("Application failed to complete successfully");
    LOG.info("client ends with value: " + result);
    System.exit(result);
  }

  public int getExitStatus() {
    return wtr.getExitCode();
  }

  private void ensureContainerRemoved() {
    docker.removeContainerCmd(containerId).exec();
  }

  public void stop() throws com.github.dockerjava.api.NotFoundException, NotModifiedException{
    if (this.containerId != null && !this.containerStoped) {
      LOG.info(String.format("Stopping DockerContainer %s", containerId));
      StopContainerCmd stopContainerCmd = docker.stopContainerCmd(containerId);
      stopContainerCmd.withTimeout(stopTimeout);
      stopContainerCmd.exec();
      containerStoped = true;
      LOG.info(String.format("DockerContainer %s  stoped", containerId));
    }
  }

  public float getProgress() {
    // TODO Implement getProgress
    return 0;
  }

  public class WaitTaskRunner implements Runnable {
    private int exitcode = ExitCode.TIMEOUT.getValue();
    private DockerClient docker;
    private String id;

    public WaitTaskRunner(DockerClient docker, String id) {
      this.docker = docker;
      this.id = id;
    }

    @Override
    public void run() {
      WaitContainerCmd wc = docker.waitContainerCmd(id);
      try {
        exitcode = wc.exec();
        containerStoped = true;
        LOG.info("waitThread end");
      } catch (NotFoundException e) {
        e.printStackTrace();
      }

    }

    public int getExitCode() {
      return this.exitcode;
    }

  }

  public class ShutdownHook implements Runnable {

    public void run() {
      LOG.info("shutdownhook start");
      if (pullProcess != null) {
        pullProcess.destroy();
      }

      if (containerId != null && !containerStoped) {
        containerStoped = true;
        StopContainerCmd stopContainerCmd = docker.stopContainerCmd(containerId);
        stopContainerCmd.withTimeout(stopTimeout);
        try {
          stopContainerCmd.exec();
        } catch (Exception e) {

          LOG.info("docker container " + containerId
                  + " has been killed", e);
        }

        LOG.info("container  stoped by shutdownhook");
      }

      if (stdoutThread != null && stdoutThread.isAlive()) {
        stdoutThread.interrupt();
      }
      if (stderrThread != null && stderrThread.isAlive()) {
        stderrThread.interrupt();
      }
      if (waitThread != null && waitThread.isAlive()) {
        waitThread.interrupt();
      }
      try {
        if (docker != null)
          docker.close();
      } catch (IOException e) {
        // TODO Auto-generated catch block
        e.printStackTrace();
      }
      LOG.info("shutdownhook end");
    }

  }
}
