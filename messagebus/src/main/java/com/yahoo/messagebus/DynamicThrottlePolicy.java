// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.messagebus;

import com.yahoo.concurrent.SystemTimer;
import com.yahoo.concurrent.Timer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * This is an implementation of the {@link ThrottlePolicy} that offers dynamic limits to the number of pending messages a
 * {@link SourceSession} is allowed to have.
 *
 * <b>NOTE:</b> By context, "pending" is referring to the number of sent messages that have not been replied to yet.
 *
 * @author Simon Thoresen Hult
 */
public class DynamicThrottlePolicy extends StaticThrottlePolicy {

    private static final long IDLE_TIME_MILLIS = 60000;
    private final Timer timer;
    private int numSent = 0;
    private int numOk = 0;
    private double resizeRate = 3;
    private long resizeTime = 0;
    private long timeOfLastMessage;
    private double efficiencyThreshold = 1.0;
    private double windowSizeIncrement = 20;
    private double windowSize = windowSizeIncrement;
    private double minWindowSize = windowSizeIncrement;
    private double decrementFactor = 2.0;
    private double maxWindowSize = Integer.MAX_VALUE;
    private double windowSizeBackOff = 0.9;
    private double weight = 1.0;
    private double localMaxThroughput = 0;
    private double maxThroughput = 0;
    private static final Logger log = Logger.getLogger(DynamicThrottlePolicy.class.getName());

    /**
     * Constructs a new instance of this policy and sets the appropriate default values of member data.
     */
    public DynamicThrottlePolicy() {
        this(SystemTimer.INSTANCE);
    }

    /**
     * Constructs a new instance of this class using the given clock to calculate efficiency.
     *
     * @param timer the timer to use
     */
    public DynamicThrottlePolicy(Timer timer) {
        this.timer = timer;
        this.timeOfLastMessage = timer.milliTime();
    }

    public double getWindowSizeIncrement() {
        return windowSizeIncrement;
    }

    public double getWindowSizeBackOff() {
        return windowSizeBackOff;
    }

    public DynamicThrottlePolicy setMaxThroughput(double maxThroughput) {
        this.maxThroughput = maxThroughput;
        return this;
    }

    @Override
    public boolean canSend(Message message, int pendingCount) {
        if ( ! super.canSend(message, pendingCount)) {
             return false;
        }
        long time = timer.milliTime();
        double elapsed = (time - timeOfLastMessage);
        if (elapsed > IDLE_TIME_MILLIS) {
            windowSize = Math.min(windowSize, pendingCount + windowSizeIncrement);
        }
        timeOfLastMessage = time;
        int windowSizeFloored = (int) windowSize;
        boolean carry = numSent < (windowSize * resizeRate) * windowSize - windowSizeFloored;
        return pendingCount < windowSizeFloored + (carry ? 1 : 0);
    }

    @Override
    public void processMessage(Message message) {
        super.processMessage(message);
        if (++numSent < windowSize * resizeRate) {
            return;
        }

        long time = timer.milliTime();
        double elapsed = time - resizeTime;
        resizeTime = time;

        double throughput = numOk / elapsed;
        numSent = 0;
        numOk = 0;


        if (maxThroughput > 0 && throughput > maxThroughput * 0.95) {
            // No need to increase window when we're this close to max.
        } else if (throughput >= localMaxThroughput) {
            localMaxThroughput = throughput;
            windowSize += weight*windowSizeIncrement;
            if (log.isLoggable(Level.FINE)) {
                log.log(Level.FINE, "windowSize " + windowSize + " throughput " + throughput + " local max " + localMaxThroughput);
            }
        } else {
            // scale up/down throughput for comparing to window size
            double period = 1;
            while(throughput * period/windowSize < 2) {
                period *= 10;
            }
            while(throughput * period/windowSize > 2) {
                period *= 0.1;
            }
            double efficiency = throughput*period/windowSize;
            if (efficiency < efficiencyThreshold) {
                windowSize = Math.min(windowSize * windowSizeBackOff, windowSize - decrementFactor * windowSizeIncrement);
                localMaxThroughput = 0;
            } else {
                windowSize += weight*windowSizeIncrement;
            }
            if (log.isLoggable(Level.FINE)) {
                log.log(Level.FINE, "windowSize " + windowSize + " throughput " + throughput + " local max " + localMaxThroughput + " efficiency " + efficiency);
            }
        }
        windowSize = Math.max(minWindowSize, windowSize);
        windowSize = Math.min(maxWindowSize, windowSize);
    }

    @Override
    public void processReply(Reply reply) {
        super.processReply(reply);
        if ( ! reply.hasErrors()) {
            ++numOk;
        }
    }

    /**
     * Sets the lower efficiency threshold at which the algorithm should perform window size back off. Efficiency is
     * the correlation between throughput and window size. The algorithm will increase the window size until efficiency
     * drops below the efficiency of the local maxima times this value.
     *
     * @param efficiencyThreshold the limit to set
     * @return this, to allow chaining
     * @see #setWindowSizeBackOff(double)
     */
    public DynamicThrottlePolicy setEfficiencyThreshold(double efficiencyThreshold) {
        this.efficiencyThreshold = efficiencyThreshold;
        return this;
    }

    /**
     * Sets the step size used when increasing window size.
     *
     * @param windowSizeIncrement the step size to set
     * @return this, to allow chaining
     */
    public DynamicThrottlePolicy setWindowSizeIncrement(double windowSizeIncrement) {
        this.windowSizeIncrement = windowSizeIncrement;
        this.windowSize = Math.max(this.minWindowSize, this.windowSizeIncrement);
        return this;
    }

    /**
     * Sets the relative step size when decreasing window size.
     *
     * @param decrementFactor the step size to set
     * @return this, to allow chaining
     */
    public DynamicThrottlePolicy setWindowSizeDecrementFactor(double decrementFactor) {
        this.decrementFactor = decrementFactor;
        return this;
    }

    /**
     * Sets the factor of window size to back off to when the algorithm determines that efficiency is not increasing.
     * A value of 1 means that there is no back off from the local maxima, and means that the algorithm will fail to
     * reduce window size to something lower than a previous maxima. This value is capped to the [0, 1] range.
     *
     * @param windowSizeBackOff the back off to set
     * @return this, to allow chaining
     */
    public DynamicThrottlePolicy setWindowSizeBackOff(double windowSizeBackOff) {
        this.windowSizeBackOff = Math.max(0, Math.min(1, windowSizeBackOff));
        return this;
    }

    /**
     * Sets the rate at which the window size is updated. The larger the value, the less responsive the resizing
     * becomes. However, the smaller the value, the less accurate the measurements become.
     *
     * @param resizeRate the rate to set
     * @return this, to allow chaining
     */
    public DynamicThrottlePolicy setResizeRate(double resizeRate) {
        this.resizeRate = resizeRate;
        return this;
    }

    /**
     * Sets the weight for this client. The larger the value, the more resources
     * will be allocated to this clients. Resources are shared between clients
     * proportionally to the set weights.
     *
     * @param weight the weight to set
     * @return this, to allow chaining
     */
    public DynamicThrottlePolicy setWeight(double weight) {
        this.weight = Math.pow(weight, 0.5);
        return this;
    }

    /**
     * Sets the maximium number of pending operations allowed at any time, in
     * order to avoid using too much resources.
     *
     * @param max the max to set
     * @return this, to allow chaining
     */
    public DynamicThrottlePolicy setMaxWindowSize(double max) {
        this.maxWindowSize = max;
        return this;
    }

    /**
     * Get the maximum number of pending operations allowed at any time.
     *
     * @return the maximum number of operations
     */
    public double getMaxWindowSize() {
        return maxWindowSize;
    }


    /**
     * Sets the minimium number of pending operations allowed at any time, in
     * order to keep a level of performance.
     *
     * @param min the min to set
     * @return this, to allow chaining
     */
    public DynamicThrottlePolicy setMinWindowSize(double min) {
        this.minWindowSize = min;
        this.windowSize = Math.max(this.minWindowSize, this.windowSizeIncrement);
        return this;
    }

    /**
     * Get the minimum number of pending operations allowed at any time.
     *
     * @return the minimum number of operations
     */
    public double getMinWindowSize() {
        return minWindowSize;
    }

    public DynamicThrottlePolicy setMaxPendingCount(int maxCount) {
        super.setMaxPendingCount(maxCount);
        maxWindowSize = maxCount;
        return this;
    }


    /**
     * Returns the maximum number of pending messages allowed.
     *
     * @return the max limit
     */
    public int getMaxPendingCount() {
        return (int) windowSize;
    }

}
