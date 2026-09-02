import json
import unittest
from pathlib import Path

from driftzero_ml.contracts import ContractError, validate_navigation_state, validate_sensor_frame

REPO = Path(__file__).resolve().parents[2]
EXAMPLES = REPO / "contracts" / "examples"


class ContractTests(unittest.TestCase):
    def test_example_sensor_frame_is_valid(self) -> None:
        payload = json.loads((EXAMPLES / "sensor_frame.accelerometer.json").read_text())
        validate_sensor_frame(payload)

    def test_example_navigation_state_is_valid(self) -> None:
        payload = json.loads((EXAMPLES / "navigation_state.sample.json").read_text())
        validate_navigation_state(payload)

    def test_sensor_frame_rejects_wrong_accelerometer_unit(self) -> None:
        payload = json.loads((EXAMPLES / "sensor_frame.accelerometer.json").read_text())
        payload["payload"]["unit"] = "rad/s"
        with self.assertRaisesRegex(ContractError, "m/s\\^2"):
            validate_sensor_frame(payload)

    def test_navigation_state_rejects_heading_of_two_pi(self) -> None:
        payload = json.loads((EXAMPLES / "navigation_state.sample.json").read_text())
        payload["motion"]["heading_rad"] = 2 * 3.141592653589793
        with self.assertRaisesRegex(ContractError, "2pi"):
            validate_navigation_state(payload)

    def test_navigation_state_rejects_short_config_hash(self) -> None:
        payload = json.loads((EXAMPLES / "navigation_state.sample.json").read_text())
        payload["provenance"]["config_hash"] = "abc"
        with self.assertRaisesRegex(ContractError, "64 hex"):
            validate_navigation_state(payload)

    def test_sensor_frame_rejects_unknown_field(self) -> None:
        payload = json.loads((EXAMPLES / "sensor_frame.accelerometer.json").read_text())
        payload["extra"] = True
        with self.assertRaisesRegex(ContractError, "unknown"):
            validate_sensor_frame(payload)


if __name__ == "__main__":
    unittest.main()
