import unittest
from select_ci_simulator import choose_ipad, version_tuple


class SimulatorSelectorTests(unittest.TestCase):
    a = "00000000-0000-0000-0000-000000000001"
    b = "00000000-0000-0000-0000-000000000002"

    def fixture(self, os_version, name="iPad Pro (11-inch)", available=True, udid=None):
        return {f"com.apple.CoreSimulator.SimRuntime.iOS-{os_version}": [
            {"name": name, "isAvailable": available, "udid": udid or self.a}]}

    def test_chooses_newest_compatible_ipad(self):
        devices = self.fixture("17-5") | self.fixture("18-5", udid=self.b)
        self.assertEqual(choose_ipad({"devices": devices}, "18.5"), self.b)

    def test_does_not_choose_future_runtime(self):
        devices = self.fixture("18-5") | self.fixture("26-2", udid=self.b)
        self.assertEqual(choose_ipad({"devices": devices}, "18.5"), self.a)

    def test_rejects_iphone_unavailable_old_and_malformed(self):
        for devices in [self.fixture("18-5", name="iPhone 16"),
                        self.fixture("18-5", available=False), self.fixture("16-4"),
                        self.fixture("18-5", udid="invalid")]:
            with self.assertRaises(ValueError):
                choose_ipad({"devices": devices}, "18.5")

    def test_ignores_other_platforms_and_empty_inventory(self):
        for payload in [{}, {"devices": {"com.apple.CoreSimulator.SimRuntime.tvOS-18-5": []}}]:
            with self.assertRaises(ValueError):
                choose_ipad(payload, "18.5")

    def test_version_parsing_and_minimum(self):
        self.assertEqual(version_tuple("17"), (17, 0, 0))
        self.assertEqual(choose_ipad({"devices": self.fixture("17-0")}, "17.0"), self.a)
        for value in ["", "18.5\n", "18.x", "18.5.0.1"]:
            with self.assertRaises(ValueError):
                version_tuple(value)


if __name__ == "__main__":
    unittest.main()
