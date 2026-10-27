package com.elfmcys.ysm.natives;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

/** The immutable OS report or a Java observation that no valid report was available. */
public record ThreadSchedulingResult(Outcome outcome, Platform platform, Observations observations, List<Step> steps,
    String detail) {
    public ThreadSchedulingResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(observations, "observations");
        steps = List.copyOf(steps);
        Objects.requireNonNull(detail, "detail");
    }

    public enum Outcome {
        UNCHANGED, APPLIED, DEGRADED, PARTIAL, FAILED, UNSUPPORTED, UNAVAILABLE, INVALID_REPORT
    }

    public enum Platform {
        UNKNOWN, WINDOWS, LINUX, ANDROID, MACOS
    }

    public enum Attribute {
        CPU_PRIORITY, SCHEDULER_POLICY, MEMORY_PRIORITY, QOS_CLASS, QOS_RELATIVE_PRIORITY;

        public int mask() {
            return 1 << ordinal();
        }
    }

    public enum Operation {
        QUERY_BEFORE, SET_CPU, BEGIN_BACKGROUND, SET_MEMORY, END_BACKGROUND, SET_SCHEDULER, SET_QOS, QUERY_AFTER
    }

    public enum StepResult {
        OK, SKIPPED, UNSUPPORTED, FAILED
    }

    public enum ErrorDomain {
        NONE, WIN32, ERRNO, PTHREAD
    }

    public record Step(Operation operation, StepResult result, ErrorDomain errorDomain, long nativeError) {
    }

    public record Observations(int mask, long cpuPriority, long schedulerPolicy, long memoryPriority, long qosClass,
        long qosRelativePriority) {
        public OptionalLong value(Attribute attribute) {
            if ((mask & attribute.mask()) == 0) {
                return OptionalLong.empty();
            }
            return OptionalLong.of(switch (attribute) {
                case CPU_PRIORITY -> cpuPriority;
                case SCHEDULER_POLICY -> schedulerPolicy;
                case MEMORY_PRIORITY -> memoryPriority;
                case QOS_CLASS -> qosClass;
                case QOS_RELATIVE_PRIORITY -> qosRelativePriority;
            });
        }
    }

    public static ThreadSchedulingResult unchanged() {
        return observation(Outcome.UNCHANGED, "");
    }

    public static ThreadSchedulingResult unavailable(String detail) {
        return observation(Outcome.UNAVAILABLE, detail);
    }

    private static ThreadSchedulingResult observation(Outcome outcome, String detail) {
        return new ThreadSchedulingResult(outcome, Platform.UNKNOWN, new Observations(0, 0, 0, 0, 0, 0), List.of(),
            detail);
    }

    static ThreadSchedulingResult decode(long[] report) {
        if (report == null) {
            return unavailable("JNI returned no report");
        }
        try {
            if (report.length < 9 || report[8] < 0 || report[8] > 8 || report.length != 9 + 4 * report[8]) {
                throw new IllegalArgumentException("Invalid report length or step count");
            }
            var outcome = element(Outcome.values(), report[0], 0, 5);
            var platform = element(Platform.values(), report[1], 1, 4);
            if (report[2] < 0 || (report[2] & ~31L) != 0) {
                throw new IllegalArgumentException("Invalid observed mask");
            }
            for (var index = 0; index < 5; index++) {
                if ((report[2] & (1L << index)) == 0 && report[3 + index] != 0) {
                    throw new IllegalArgumentException("Unobserved attribute has a value");
                }
            }
            var observations = new Observations((int)report[2], report[3], report[4], report[5], report[6], report[7]);
            var steps = new ArrayList<Step>((int)report[8]);
            for (var index = 9; index < report.length; index += 4) {
                var operation = element(Operation.values(), report[index] - 1, 0, 7);
                var result = element(StepResult.values(), report[index + 1], 0, 3);
                var domain = element(ErrorDomain.values(), report[index + 2], 0, 3);
                var error = report[index + 3];
                if (error < 0 || domain == ErrorDomain.NONE && error != 0
                    || (result == StepResult.OK || result == StepResult.SKIPPED) && error != 0
                    || domain == ErrorDomain.WIN32 && error > 0xffff_ffffL) {
                    throw new IllegalArgumentException("Invalid native error");
                }
                steps.add(new Step(operation, result, domain, error));
            }
            return new ThreadSchedulingResult(outcome, platform, observations, steps, "");
        } catch (IllegalArgumentException failure) {
            return observation(Outcome.INVALID_REPORT, failure.getMessage());
        }
    }

    private static <T> T element(T[] values, long code, int minimum, int maximum) {
        if (code < minimum || code > maximum) {
            throw new IllegalArgumentException("Unknown report enum: " + code);
        }
        return values[(int)code];
    }
}
