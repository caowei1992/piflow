package cn.piflow

import java.util.Date
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{CountDownLatch, TimeUnit}

import scala.collection.mutable.{ArrayBuffer, Map => MMap}
import cn.piflow.Execution
import cn.piflow.util._
import org.apache.spark.launcher.SparkAppHandle.State
import org.apache.spark.launcher.{SparkAppHandle, SparkLauncher}
import org.apache.spark.sql.SparkSession

/**
  * Created by bluejoe on 2018/6/27.
  */


trait FlowGroup extends ProjectEntry{

  def addFlow(name: String, flow: Flow, con: Condition[FlowGroupExecution] = Condition.AlwaysTrue[FlowGroupExecution]);

  def mapFlowWithConditions(): Map[String, (Flow, Condition[FlowGroupExecution])];

  def getFlowGroupName(): String;

  def setFlowGroupName(flowGroupName : String): Unit;
}


class FlowGroupImpl extends FlowGroup {

  var name = ""
  var uuid = ""

  val _mapFlowWithConditions = MMap[String, (Flow, Condition[FlowGroupExecution])]();

  def addFlow(name: String, flow: Flow, con: Condition[FlowGroupExecution] = Condition.AlwaysTrue[FlowGroupExecution]) = {
    _mapFlowWithConditions(name) = flow -> con;
  }

  def mapFlowWithConditions(): Map[String, (Flow, Condition[FlowGroupExecution])] = _mapFlowWithConditions.toMap;

  override def getFlowGroupName(): String = {
    this.name
  }

  override def setFlowGroupName(flowGroupName: String): Unit = {
    this.name = flowGroupName
  }
}

trait FlowGroupExecution extends Execution{

  def isFlowGroupCompleted() : Boolean;

  def stop(): Unit;

  def awaitTermination(): Unit;

  def awaitTermination(timeout: Long, unit: TimeUnit): Unit;

  def groupId(): String;

}

class FlowGroupExecutionImpl(fg: FlowGroup, runnerContext: Context, runner: Runner) extends FlowGroupExecution {
  val flowGroupContext = createContext(runnerContext);
  val flowGroupExecution = this;

  val id : String = "group_" + IdGenerator.uuid() ;

  val mapFlowWithConditions: Map[String, (Flow, Condition[FlowGroupExecution])] = fg.mapFlowWithConditions();
  val completedProcesses = MMap[String, Boolean]();
  completedProcesses ++= mapFlowWithConditions.map(x => (x._1, false));
  val numWaitingProcesses = new AtomicInteger(mapFlowWithConditions.size);

  val startedProcesses = MMap[String, SparkAppHandle]();
  val startedProcessesAppID = MMap[String, String]()

  val execution = this;
  val POLLING_INTERVAL = 1000;
  val latch = new CountDownLatch(1);
  var running = true;


  val runnerListener = runner.getListener()


  def isFlowGroupCompleted(): Boolean = {
    completedProcesses.foreach( en =>{
      if(en._2 == false){
        return false
      }
    })
    return true
  }

  private def startProcess(name: String, flow: Flow, groupId : String): Unit = {

    println("Start flow " + name + " , groupId: " + groupId)
    println(flow.getFlowJson())

    var flowJson = flow.getFlowJson()
    flowJson = flowJson.replaceAll("}","}\n")

    var appId : String = ""
    val countDownLatch = new CountDownLatch(1)

    val handle = FlowLauncher.launch(flow).startApplication( new SparkAppHandle.Listener {
      override def stateChanged(handle: SparkAppHandle): Unit = {
        appId = handle.getAppId
        val sparkAppState = handle.getState
        if(appId != null){
          println("Spark job with app id: " + appId + ",\t State changed to: " + sparkAppState)
        }else{
          println("Spark job's state changed to: " + sparkAppState)
        }

        if(H2Util.getFlowState(appId).equals(FlowState.COMPLETED)){
          completedProcesses(flow.getFlowName()) = true;
          numWaitingProcesses.decrementAndGet();
        }

        if (handle.getState().isFinal){
          countDownLatch.countDown()
          println("Task is finished!")
        }
      }

      override def infoChanged(handle: SparkAppHandle): Unit = {


      }
    }
    )


    while (handle.getAppId == null){
      Thread.sleep(1000)
    }
    appId = handle.getAppId

    //wait flow process started
    while(H2Util.getFlowProcessId(appId).equals("")){
      Thread.sleep(1000)
    }
    H2Util.updateFlowGroupId(appId,groupId)
    startedProcesses(name) = handle;
    startedProcessesAppID(name) = appId

  }

  val pollingThread = new Thread(new Runnable() {
    override def run(): Unit = {

      runnerListener.onFlowGroupStarted(flowGroupContext)

      try{

        while (numWaitingProcesses.get() > 0) {
          val todos = ArrayBuffer[(String, Flow)]();
          mapFlowWithConditions.foreach { en =>
            if (!startedProcesses.contains(en._1) && en._2._2.matches(execution)) {
              todos += (en._1 -> en._2._1);
            }
          }

          startedProcesses.synchronized {
            todos.foreach(en => startProcess(en._1, en._2, id));
          }

          Thread.sleep(POLLING_INTERVAL);
        }


        runnerListener.onFlowGroupCompleted(flowGroupContext)

      }catch {
        case e: Throwable =>
          runnerListener.onFlowGroupFailed(flowGroupContext);
          throw e;
      }
      finally {
        latch.countDown();
        finalizeExecution(true);
      }

    }
  });

  pollingThread.start();

  override def awaitTermination(): Unit = {
    latch.await();
    finalizeExecution(true);
  }

  override def stop(): Unit = {
    finalizeExecution(false);
    //runnerListener.onFlowGroupStoped(flowGroupContext)
  }

  override def awaitTermination(timeout: Long, unit: TimeUnit): Unit = {
    if (!latch.await(timeout, unit))
      finalizeExecution(false);
  }

  private def finalizeExecution(completed: Boolean): Unit = {
    if (running) {
      if (!completed) {

        //startedProcesses.filter(x => isEntryCompleted(x._1)).map(_._2).foreach(_.stop());
        startedProcesses.synchronized{
          startedProcesses.filter(x => !isEntryCompleted(x._1)).foreach(x => {

            x._2.stop()
            val appID: String = startedProcessesAppID.getOrElse(x._1,"")
            if(!appID.equals("")){
              println("Stop Flow " + appID + " by FlowLauncher!")
              FlowLauncher.stop(appID)
            }

          });
          pollingThread.interrupt();
        }

      }

      running = false;
    }
  }

  private def createContext(runnerContext: Context): FlowGroupContext = {
    new CascadeContext(runnerContext) with FlowGroupContext {
      override def getFlowGroup(): FlowGroup = fg

      override def getFlowGroupExecution(): FlowGroupExecution = flowGroupExecution
    };
  }

  override def isEntryCompleted(name: String): Boolean = {
    completedProcesses(name);
  }

  override def groupId(): String = id;
}
