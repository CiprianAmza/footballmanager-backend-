package com.footballmanagergamesimulator.tools;

import com.sun.tools.attach.VirtualMachine;
import javax.management.ObjectName;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

public final class SnapshotViaJmx {
    public static void main(String[] args) throws Exception {
        VirtualMachine vm = VirtualMachine.attach(args[0]);
        String address;
        try {
            address = vm.startLocalManagementAgent();
        } finally {
            vm.detach();
        }
        try (var connector = JMXConnectorFactory.connect(new JMXServiceURL(address))) {
            var server = connector.getMBeanServerConnection();
            ObjectName name = new ObjectName("footballmanager.tools:type=LocalH2Snapshot");
            if (!server.isRegistered(name)) {
                server.createMBean(LocalH2Snapshot.class.getName(), name);
            }
            Object database = server.invoke(name, "snapshot", new Object[]{args[1]},
                    new String[]{String.class.getName()});
            System.out.println(database);
        }
    }
}
