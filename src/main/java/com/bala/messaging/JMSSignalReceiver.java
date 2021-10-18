package com.bala.messaging;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.ServiceLoader;

import javax.jms.BytesMessage;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageListener;

import org.apache.commons.io.input.ClassLoaderObjectInputStream;
import org.jbpm.process.instance.impl.ProcessInstanceImpl;
import org.jbpm.process.instance.impl.util.VariableUtil;
import org.jbpm.workflow.instance.node.EventNodeInstance;
import org.kie.api.runtime.manager.RuntimeEngine;
import org.kie.api.runtime.manager.RuntimeManager;
import org.kie.api.runtime.process.NodeInstance;
import org.kie.api.runtime.process.ProcessInstance;
import org.kie.api.runtime.process.WorkflowProcessInstance;
import org.kie.internal.runtime.manager.InternalRuntimeManager;
import org.kie.internal.runtime.manager.RuntimeManagerIdFilter;
import org.kie.internal.runtime.manager.RuntimeManagerRegistry;
import org.kie.internal.runtime.manager.context.ProcessInstanceIdContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JMSSignalReceiver
 */
@org.jboss.ejb3.annotation.ResourceAdapter(value = "activemq-rar.rar") // ONLY FOR REMOTE AMQ
public class JMSSignalReceiver implements MessageListener {
    private static final Logger logger = LoggerFactory.getLogger(JMSSignalReceiver.class);

    private static final ServiceLoader<RuntimeManagerIdFilter> runtimeManagerIdFilters = ServiceLoader
            .load(RuntimeManagerIdFilter.class);

    @Override
    public void onMessage(Message message) {
        if (message instanceof BytesMessage) {

            String deploymentId;
            Long processInstanceId;
            String signal;
            Object data;

            BytesMessage bytesMessage = (BytesMessage) message;
            RuntimeManager runtimeManager = null;
            RuntimeEngine engine = null;

            int retries = Integer.getInteger("com.sample.signal.retries", 30);
            int delay = Integer.getInteger("com.sample.signal.retry.interval", 5000);

            try {
                deploymentId = (String) bytesMessage.getObjectProperty("DeploymentId");
                signal = (String) bytesMessage.getObjectProperty("Signal");
                processInstanceId = (Long) bytesMessage.getObjectProperty("ProcessInstanceId");

                logger.debug("Deployment id '{}', Signal '{}', ProcessInstanceId '{}'", deploymentId, signal,
                        processInstanceId);

                Collection<String> availableRuntimeManagers = matchDeployments(deploymentId,
                        RuntimeManagerRegistry.get().getRegisteredIdentifiers());

                for (String matchedDeploymentId : availableRuntimeManagers) {
                    try {
                        runtimeManager = RuntimeManagerRegistry.get().getManager(matchedDeploymentId);

                        if (runtimeManager == null) {
                            throw new IllegalStateException(
                                    "There is no runtime manager for deployment " + matchedDeploymentId);
                        }
                        logger.debug(
                                "RuntimeManager found for deployment id {}, reading message content with custom class loader of the deployment",
                                matchedDeploymentId);
                        data = readData(bytesMessage,
                                ((InternalRuntimeManager) runtimeManager).getEnvironment().getClassLoader());
                        logger.debug("Data read successfully with output {}", data);

                        engine = runtimeManager.getRuntimeEngine(ProcessInstanceIdContext.get(processInstanceId));
                        logger.debug("=========================================");
                        logger.debug("Signal is:" + signal);
                        logger.debug("=========================================");

                        // perform operation for signal
                        if (signal != null) {
                            if (processInstanceId != null) {
                                logger.debug(
                                        "About to signal process instance with id {} and event data {} with signal {}",
                                        processInstanceId, data, signal);

                                int attempt = 0;
                                while (true) {
                                    if (attempt > retries) {
                                        // TODO: If the retries exceed the set limit, push the message to a DLQ
                                        break;
                                    }
                                    // get process instance in readonly mode to avoid locks
                                    ProcessInstance processInstance = engine.getKieSession()
                                            .getProcessInstance(processInstanceId, true);

                                    // use runtime snapshot and get the active nodes
                                    if (processInstance != null) {
                                        ((ProcessInstanceImpl) processInstance).setProcess(engine.getKieSession()
                                                .getKieBase().getProcess(processInstance.getProcessId()));
                                        Collection<NodeInstance> activeNodes = ((WorkflowProcessInstance) processInstance)
                                                .getNodeInstances();

                                        // get only active signal nodes
                                        Collection<String> activeNodesComposite = new ArrayList<>();
                                        getActiveSignalNodes(activeNodes, activeNodesComposite);
                                        
                                        logger.debug("=================================");
                                        logger.debug("All active signals in the process are: "
                                                + activeNodesComposite);

                                        // if the incoming signal is in active state, fire it
                                        if (activeNodesComposite.contains(signal)) {
                                            logger.debug("=============== Firing Signal for " + processInstanceId
                                                    + " ==================");
                                            engine.getKieSession().signalEvent(signal, data, processInstanceId);
                                            break;
                                        } else { // wait and retry
                                            attempt++;
                                            try {
                                                Thread.sleep(delay);
                                            } catch (InterruptedException e1) {
                                                logger.trace("retry sleeping got interrupted");
                                            }
                                        }
                                    }else{
                                    // broadcast the signal if the customer needs it
                                    }
                                }

                            } else {
                                logger.debug("About to broadcast signal {} and event data {}", signal, data);
                                runtimeManager.signalEvent(signal, data);
                            }
                            logger.debug("Signal completed successfully for signal {} with data {}", signal, data);
                        } else {
                            logger.warn("No signal or workitem id is given, skipping this message");
                        }
                    } catch (Exception e) {
                        logger.error("Unexpected exception while signaling: {}", e.getMessage(), e);
                    } finally {
                        if (runtimeManager != null && engine != null) {
                            runtimeManager.disposeRuntimeEngine(engine);
                        }
                    }
                }
            } catch (

            Exception e) {
                logger.error("Unexpected exception while processing signal JMS message: {}", e.getMessage(), e);
            }
        }

    }

    // 1. collect only active signal nodes
    // 2. to be extended for signals on boundary as well
    // TODO: if active signals on the boundary of nodes are required, extend this code further as required
    Collection<String> getActiveSignalNodes(Collection<NodeInstance> activeNodes,
            Collection<String> activeNodesComposite) {
        for (NodeInstance nodeInstance : activeNodes) {
            if (nodeInstance instanceof EventNodeInstance) {
                String type = ((EventNodeInstance) nodeInstance).getEventNode().getType();
                if (type != null && !type.startsWith("Message-")) {
                    activeNodesComposite.add(VariableUtil.resolveVariable(type, nodeInstance));
                }
            }
        }

        return activeNodesComposite;
    }

    protected Object readData(BytesMessage message, ClassLoader cl) throws JMSException, Exception {
        Object data = null;
        if (message.getBodyLength() > 0) {
            byte[] reqData = new byte[(int) message.getBodyLength()];

            message.readBytes(reqData);
            if (reqData != null) {
                ObjectInputStream in = null;
                try {
                    in = new ClassLoaderObjectInputStream(cl, new ByteArrayInputStream(reqData));
                    data = in.readObject();
                } catch (IOException e) {
                    logger.warn("Exception while serializing context data", e);

                } finally {
                    if (in != null) {
                        in.close();
                    }
                }
            }
        }
        return data;
    }

    protected Collection<String> matchDeployments(String deploymentId, Collection<String> availableDeployments) {
        if (availableDeployments == null || availableDeployments.isEmpty()) {
            return Collections.emptyList();
        }
        Collection<String> matched = new ArrayList<String>();
        for (RuntimeManagerIdFilter filter : runtimeManagerIdFilters) {
            matched = filter.filter(deploymentId, availableDeployments);

            if (matched != null && !matched.isEmpty()) {
                return matched;
            }
        }

        // nothing matched return given deployment id
        return Collections.singletonList(deploymentId);
    }
}
