package org.cloudbus.cloudsim.examples;
import org.jfree.chart.plot.SpiderWebPlot;
import org.jfree.data.xy.DefaultXYDataset;
import java.awt.Color;
import java.awt.Font;
import org.cloudbus.cloudsim.*;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.HostEntity;
import org.cloudbus.cloudsim.provisioners.*;
import java.text.DecimalFormat;
import java.util.*;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartFrame;
import org.jfree.chart.JFreeChart;
import org.jfree.data.category.DefaultCategoryDataset;
import org.jfree.chart.ChartUtils; // For saving charts as images
import java.io.File;
import java.io.IOException;

public class EnhancedMultiTierCloudSimulation {
    private static List<Cloudlet> cloudletList;
    private static List<Vm> vmList;
    private static List<Host> hostList;
    private static DatacenterBroker broker;

    public static void main(String[] args) {
        Log.println("Starting Enhanced Multi-Tier Cloud Simulation...");
        try {
            // Initialize CloudSim
            int numUsers = 1;
            Calendar calendar = Calendar.getInstance();
            boolean traceFlag = false;
            CloudSim.init(numUsers, calendar, traceFlag);

            // Create multi-tier datacenters
            Datacenter frontendDatacenter = createTieredDatacenter("Frontend-DC", 2, 1000);
            Datacenter backendDatacenter = createTieredDatacenter("Backend-DC", 2, 2000);

            // Create Broker
            broker = createBroker();

            // Create multi-tier infrastructure
            createMultiTierInfrastructure(broker.getId());

            // Submit VMs and Cloudlets
            broker.submitGuestList(vmList);
            broker.submitCloudletList(cloudletList);

            // Simulate host failure after 50 seconds (delayed to observe impact)
            new Thread(() -> {
                try {
                    Thread.sleep(50); // Delay host failure
                    simulateHostFailure(backendDatacenter, 1); // Fail host 1 in backend tier
                } catch (Exception e) {
                    Log.println("Error during host failure simulation: " + e.getMessage());
                    e.printStackTrace();
                }
            }).start();

            // Start simulation
            CloudSim.startSimulation();
            CloudSim.stopSimulation();

            // Print results
            printResults();

            // Visualize cloudlet execution results
            visualizeCloudletExecutionResults();

            Log.println("Simulation completed!");
        } catch (Exception e) {
            Log.println("Simulation terminated due to errors: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static Datacenter createTieredDatacenter(String name, int hosts, int mips) throws Exception {
        List<Host> hostList = new ArrayList<>();
        for (int i = 0; i < hosts; i++) {
            // Create PEs with specified MIPS
            List<Pe> peList = new ArrayList<>();
            peList.add(new Pe(0, new PeProvisionerSimple(mips)));
            peList.add(new Pe(1, new PeProvisionerSimple(mips)));

            // Standard host configuration
            hostList.add(
                new Host(
                    i,
                    new RamProvisionerSimple(4096),  // 4GB RAM
                    new BwProvisionerSimple(10000),  // 10Gbps
                    1000000,                        // 1TB storage
                    peList,
                    new VmSchedulerTimeShared(peList)
                )
            );
        }

        DatacenterCharacteristics characteristics = new DatacenterCharacteristics(
            "x86", "Linux", "Xen", hostList, 10.0, 3.0, 0.05, 0.001, 0.0
        );

        return new Datacenter(name, characteristics, 
            new VmAllocationPolicySimple(hostList), 
            new LinkedList<>(), 0);
    }

    private static void createMultiTierInfrastructure(int brokerId) {
        // Create hosts for two tiers
        hostList = new ArrayList<>();
        hostList.addAll(createHosts(2, 1000, "frontend")); // Frontend tier hosts (lower capacity)
        hostList.addAll(createHosts(2, 2000, "backend"));  // Backend tier hosts (higher capacity)

        // Create VMs for different tiers
        vmList = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            vmList.add(new Vm(i, brokerId, 1000, 1, 1024, 1000, 10000, "Xen", 
                new CloudletSchedulerTimeShared()));
        }
        for (int i = 2; i < 7; i++) {
            vmList.add(new Vm(i, brokerId, 2000, 2, 2048, 2000, 20000, "Xen", 
                new CloudletSchedulerTimeShared()));
        }

        // Create cloudlets with dynamic workloads
        cloudletList = new ArrayList<>();
        UtilizationModel stochasticModel = new UtilizationModelStochastic();
        for (int i = 0; i < 8; i++) {
            long length = i < 2 ? (long) (Math.random() * 40000 + 20000) : (long) (Math.random() * 80000 + 60000);
            Cloudlet cloudlet = new Cloudlet(
                i, 
                length, 
                1, 
                300, 300, 
                stochasticModel, stochasticModel, stochasticModel
            );
            cloudlet.setUserId(brokerId);
            cloudletList.add(cloudlet);
        }
    }

    private static List<Host> createHosts(int count, int mips, String tier) {
        List<Host> hosts = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            List<Pe> peList = new ArrayList<>();
            peList.add(new Pe(0, new PeProvisionerSimple(mips)));
            peList.add(new Pe(1, new PeProvisionerSimple(mips)));

            hosts.add(new Host(
                i + (tier.equals("frontend") ? 0 : 100),
                new RamProvisionerSimple(tier.equals("frontend") ? 2048 : 4096),
                new BwProvisionerSimple(10000),
                1000000,
                peList,
                new VmSchedulerTimeShared(peList)
            ));
        }
        return hosts;
    }

    private static void simulateHostFailure(Datacenter datacenter, int hostId) {
        Host host = (Host) datacenter.getHostList().get(hostId);
        Log.println("SIMULATING FAILURE: Host " + hostId + " at time " + CloudSim.clock());

        // Get the list of VMs running on the failed host
        List<Vm> vmList = new ArrayList<>(host.getVmList()); // Create a copy to avoid concurrent modification

        // Migrate each VM to an alternative host
        for (Vm vm : vmList) {
            int alternativeHostId = findAlternativeHost(datacenter, hostId);
            if (alternativeHostId != -1) {
                Host alternativeHost = (Host) datacenter.getHostList().get(alternativeHostId);
                if (alternativeHost.isSuitableForVm(vm)) {
                    host.getVmList().remove(vm);
                    alternativeHost.getVmList().add(vm);
                    vm.setHost(alternativeHost); // Update the VM's host reference
                    Log.println("Migrated VM #" + vm.getId() + " to Host #" + alternativeHostId);
                } else {
                    Log.println("Alternative Host #" + alternativeHostId + " does not have sufficient resources for VM #" + vm.getId());
                }
            } else {
                Log.println("No alternative host found for VM #" + vm.getId());
            }
        }

        // Mark the host as failed
        host.setFailed(true);
    }

    private static int findAlternativeHost(Datacenter datacenter, int failedHostId) {
        for (HostEntity host : datacenter.getHostList()) {
            if (host.getId() != failedHostId && !host.isFailed()) {
                return host.getId();
            }
        }
        return -1;
    }

    @SuppressWarnings("deprecation")
    private static void printResults() {
        List<Cloudlet> finishedCloudlets = broker.getCloudletReceivedList();
        DecimalFormat df = new DecimalFormat("###.##");

        Log.println("\nCloudlet Execution Results:");
        Log.println("ID\tStatus\tDC\tVM\tTime\tStart\tFinish");
        for (Cloudlet cloudlet : finishedCloudlets) {
            Log.println(
                cloudlet.getCloudletId() + "\t" +
                (cloudlet.getStatus() == Cloudlet.CloudletStatus.SUCCESS ? "SUCCESS" : "FAILED") + "\t" +
                cloudlet.getResourceId() + "\t" +
                cloudlet.getVmId() + "\t" +
                df.format(cloudlet.getActualCPUTime()) + "\t" +
                df.format(cloudlet.getExecStartTime()) + "\t" +
                df.format(cloudlet.getFinishTime())
            );
        }
    }

    private static DatacenterBroker createBroker() {
        try {
            return new DatacenterBroker("Broker");
        } catch (Exception e) {
            Log.println("Error creating broker: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    // private static void visualizeCloudletExecutionResults() {
    //     DefaultCategoryDataset dataset = new DefaultCategoryDataset();
    //     List<Cloudlet> finishedCloudlets = broker.getCloudletReceivedList();
    //     for (Cloudlet cloudlet : finishedCloudlets) {
    //         dataset.addValue(cloudlet.getActualCPUTime(), "Execution Time", "Cloudlet #" + cloudlet.getCloudletId());
    //     }

    //     JFreeChart barChart = ChartFactory.createBarChart(
    //         "Cloudlet Execution Results",    
    //         "Execution Time (seconds)",     
    //         "Cloudlets",                    
    //         dataset,                        
    //         org.jfree.chart.plot.PlotOrientation.HORIZONTAL, 
    //         true,                           
    //         true,                        
    //         false                          
    //     );
    
    //     barChart.getTitle().setPaint(java.awt.Color.BLUE); // Set title color
    //     barChart.setBackgroundPaint(java.awt.Color.WHITE);  // Set background color

    //     try {
    //         ChartUtils.saveChartAsPNG(new File("cloudlet_execution_results.png"), barChart, 800, 600);
    //         Log.println("Visualization saved as 'cloudlet_execution_results.png'");
    //     } catch (IOException e) {
    //         Log.println("Error saving visualization: " + e.getMessage());
    //         e.printStackTrace();
    //     }
    // }

//     private static void visualizeCloudletExecutionResults() {
//     List<Cloudlet> finishedCloudlets = broker.getCloudletReceivedList();
    
//     SpiderWebPlot plot = new SpiderWebPlot(createSpiderDataset(finishedCloudlets));
//     plot.setStartAngle(54);
//     plot.setInteriorGap(0.40);
//     plot.setWebFilled(true);
//     plot.setSeriesPaint(0, new Color(156, 20, 20, 90)); 
//     plot.setBackgroundPaint(Color.WHITE);
//     plot.setOutlineVisible(false);
    
//     JFreeChart radarChart = new JFreeChart(
//         "Cloudlet Execution Times",
//         new Font("Arial", Font.BOLD, 16),
//         plot,
//         true
//     );
    
//     radarChart.setBackgroundPaint(Color.WHITE);
    
//     try {
//         ChartUtils.saveChartAsPNG(new File("cloudlet_execution_results.png"), radarChart, 800, 800);
//         Log.println("Visualization saved as 'cloudlet_execution_results.png'");
//     } catch (IOException e) {
//         Log.println("Error saving visualization: " + e.getMessage());
//         e.printStackTrace();
//     }
// }

// private static DefaultCategoryDataset createSpiderDataset(List<Cloudlet> cloudlets) {
//     DefaultCategoryDataset dataset = new DefaultCategoryDataset();
    
//     for (Cloudlet cloudlet : cloudlets) {
//         dataset.addValue(
//             cloudlet.getActualCPUTime(),                  
//             "Execution Time",                             
//             "Cloudlet #" + cloudlet.getCloudletId()       
//             );
//         }
//         return dataset;
//     }

    private static void visualizeCloudletExecutionResults() {
    DefaultCategoryDataset dataset = new DefaultCategoryDataset();
    List<Cloudlet> finishedCloudlets = broker.getCloudletReceivedList();
    for (Cloudlet cloudlet : finishedCloudlets) {
        dataset.addValue(cloudlet.getActualCPUTime(), "Execution Time", "Cloudlet #" + cloudlet.getCloudletId());
    }

    JFreeChart areaChart = ChartFactory.createAreaChart(
        "Cloudlet Execution Results",           // Chart title
        "Cloudlets",                           // X-axis label
        "Execution Time (seconds)",            // Y-axis label
        dataset,                               // Dataset
        org.jfree.chart.plot.PlotOrientation.VERTICAL,
        true,                                  // Include legend
        true,                                  // Include tooltips
        false                                  // No URLs
    );

    // Customize the chart
    areaChart.getTitle().setPaint(java.awt.Color.BLUE);
    areaChart.setBackgroundPaint(java.awt.Color.WHITE);

    // Save the chart
    try {
        ChartUtils.saveChartAsPNG(new File("cloudlet_execution_results.png"), areaChart, 800, 600);
        Log.println("Visualization saved as 'cloudlet_execution_results.png'");
    } catch (IOException e) {
        Log.println("Error saving visualization: " + e.getMessage());
        e.printStackTrace();
    }
}
}
