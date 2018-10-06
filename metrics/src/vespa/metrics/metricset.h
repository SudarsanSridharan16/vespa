// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
/**
 * \class metrics::MetricSet
 * \ingroup metrics
 *
 * \brief Class containing a set of metrics.
 *
 * This is a class to bundle related metrics. Note that the metricsset is
 * itself a metric so this generates a tree where metricssets are leaf nodes.
 */
#pragma once

#include "metric.h"

namespace metrics {

class MetricSet : public Metric
{
    std::vector<Metric*> _metricOrder; // Keep added order for reporting
    bool _registrationAltered; // Set to true if metrics have been
                               // registered/unregistered since last time
                               // it was reset

public:
    MetricSet(const String& name, const String& tags,
              const String& description, MetricSet* owner = 0);

    MetricSet(const String& name, Tags dimensions,
              const String& description, MetricSet* owner = 0);

    MetricSet(const MetricSet&, std::vector<Metric::UP> &ownerList,
              CopyType, MetricSet* owner = 0, bool includeUnused = false);
    ~MetricSet();

    // If no path, this metric is not registered within another
    bool isTopSet() const { return _owner == 0; }

    /**
     * Returns true if registration has been altered since it was last
     * cleared. Used by the metric manager to know when it needs to recalculate
     * which consumers will see what.
     */
    bool isRegistrationAltered() const { return _registrationAltered; }
    /** Clear all registration altered flags. */
    void clearRegistrationAltered();

    void registerMetric(Metric& m);
    void unregisterMetric(Metric& m);

    MetricSet* clone(std::vector<Metric::UP> &ownerList, CopyType type,
                     MetricSet* owner, bool includeUnused = false) const override;

    void reset() override;

    bool visit(MetricVisitor&, bool tagAsAutoGenerated = false) const override;

    bool logEvent(const String& fullName) const override;

    void print(std::ostream&, bool verbose, const std::string& indent, uint64_t secondsPassed) const override;

    // These should never be called on metrics set.
    int64_t getLongValue(stringref id) const override;
    double getDoubleValue(stringref id) const override;

    const Metric* getMetric(const String& name) const;
    Metric* getMetric(const String& name) {
        return const_cast<Metric*>(
                const_cast<const MetricSet*>(this)->getMetric(name));
    }

    void addToSnapshot(Metric& m, std::vector<Metric::UP> &o) const override { addTo(m, &o); }

    const std::vector<Metric*>& getRegisteredMetrics() const { return _metricOrder; }

    bool used() const override;
    void addMemoryUsage(MemoryConsumption&) const override;

    /** Update names using the given name hash, to utilize ref counting. */
    void updateNames(NameHash&) const override;
    void printDebug(std::ostream&, const std::string& indent="") const override;
    bool isMetricSet() const override { return true; }
    void addToPart(Metric& m) const override { addTo(m, 0); }

private:
        // Do not generate default copy constructor or assignment operator
        // These would screw up metric registering
    MetricSet(const MetricSet&);
    MetricSet& operator=(const MetricSet&);

    void tagRegistrationAltered();
    const Metric* getMetricInternal(const String& name) const;

    virtual void addTo(Metric&, std::vector<Metric::UP> *ownerList) const;
};

} // metrics

