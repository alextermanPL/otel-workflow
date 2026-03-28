package com.plumery.workflow.testworkflow.otel

import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.config.MeterFilter
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig
import io.quarkus.arc.Unremovable
import jakarta.enterprise.context.ApplicationScoped
import java.util.concurrent.TimeUnit

/**
 * Extends histogram bucket boundaries for all Temporal SDK metrics.
 *
 * The Temporal SDK default caps at 30s. Buckets here cover 500ms–30min.
 *
 * DistributionStatisticConfig SLO values for Timers are in nanoseconds.
 */
@ApplicationScoped
@Unremovable
class HistogramConfig : MeterFilter {

    private fun s(seconds: Double): Double = TimeUnit.SECONDS.toNanos(1) * seconds

    override fun configure(id: Meter.Id, config: DistributionStatisticConfig): DistributionStatisticConfig {
        if (id.name.startsWith("temporal")) {
            return DistributionStatisticConfig.builder()
                .serviceLevelObjectives(
                    s(0.5), s(1.0), s(2.0), s(5.0), s(10.0), s(20.0), s(30.0),
                    s(60.0), s(120.0), s(300.0), s(600.0), s(900.0), s(1200.0), s(1800.0)
                )
                .build()
                .merge(config)
        }
        return config
    }
}
