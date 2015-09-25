---
title: Blue-Green Deployment
---

# Blue-Green Deployment

Blue-green deployment is a way to safely deploy applications that are serving live traffic by creating two versions of an application (BLUE and GREEN). To deploy a new version of the application, you will drain all traffic, requests, and pending operations from the current version of the application, switch to the new version, and then turn off the old version. Blue-green deployment eliminates application downtime and allows you to quickly roll back to the BLUE version of the application if necessary.

For an overview of the process, here’s [a great article by Martin Fowler](http://martinfowler.com/bliki/BlueGreenDeployment.html).

## Requirements

- A Marathon based app.
    - The application should have health checks which accurately reflect the health of the application.
- The app must expose a metric endpoint to determine whether the app has any pending operations. For example, the application could expose a global atomic counter of the number of currently queued DB transactions.
- The [jq] (https://stedolan.github.io/jq/) command-line JSON processor.

## Procedure

We will replace the current app version (BLUE) with a new version (GREEN).

1. Launch the new version of the app on Marathon. Use a unique app ID, such as the git commit. This app is the GREEN app.

    ```sh
    # launch green
    dcos marathon app add green-app.json
    ```

2. Scale GREEN app instances by 1 or more. Initially (starting from 0 instances), set the number of app instances to the minimum required to serve traffic. Remember, no traffic will arrive yet: we haven't registered at the load balancer.

    ```sh
    # scale green
    dcos marathon app update /green-app instances=1
    ```

3. Wait until all tasks from the GREEN app have passed health checks. This step requires [jq] (https://stedolan.github.io/jq/).

    ```sh
    # wait until healthy
    dcos marathon app show /green-app | jq '.tasks[].healthCheckResults[] | select (.alive == false)'
    ```

4. Use the code snippet above to check that all instances of GREEN are still healthy. Abort the deployment and begin rollback if you see unexpected behavior.

5. Add the new task instances from the GREEN app to the load balancer pool.

6. Pick one or more task instances from the current (BLUE) version of the app.

    ```sh
    # pick tasks from blue
    dcos marathon task list /blue-app
    ```

7. Update the load balancer configuration to remvoe the task instances above from the BLUE app pool.

8. Wait until the task instances from the BLUE app have 0 pending operations. Use the metrics endpoint in the application to determine the number of pending operations.

9. Once all operations are complete from the BLUE tasks, kill and scale the BLUE app using [the API] (https://mesosphere.github.io/marathon/docs/rest-api.html#post-v2-tasks-delete). In the snippet below, <hosturl> is the hostname of your master cluster prefixed with ``http://``.

    ```sh
    # kill and scale blue tasks
    echo "{\"ids\":[\"<task_id>\"]}" | curl -H "Content-Type: application/json" -X POST -d @- <hosturl>/marathon/v2/tasks/delete?scale=true
    ```

    This Marathon operation will remove specific instances (the ones with 0 pending operations) and prevent them from being restarted.

10. Repeat steps 2-9 until there are no more BLUE tasks.

11. Remove the BLUE app from Marathon.
    
    ```sh
    # remove blue
    dcos marathon app remove /blue-app
    ```