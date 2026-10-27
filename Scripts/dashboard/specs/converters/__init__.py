"""One converter per test type; each returns a plan dict the runner turns into an allowlisted command."""
from specs.converters import (to_accessibility, to_api_suite, to_crawler_args, to_keyword,  # noqa: F401
                              to_perf_plan)

CONVERTERS = {"ui": to_keyword, "api": to_api_suite, "performance": to_perf_plan,
              "crawler": to_crawler_args, "accessibility": to_accessibility}


def convert(spec, inputs_dir):
    return CONVERTERS[spec["type"]].convert(spec, inputs_dir)
