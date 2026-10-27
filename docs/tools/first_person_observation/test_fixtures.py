import math
import unittest

from generate_fixtures import local, oracle, parts


class FixtureOracleTest(unittest.TestCase):
    def test_quarter_turn_around_declared_pivot(self):
        matrix = local({"pivot": [16, 0, 0]}, {"rotation": [0, 0, math.pi / 2]})
        point = [sum(matrix[row][column] * [2, 0, 0, 1][column] for column in range(4))
                 for row in range(4)]
        for actual, expected in zip(point, [1, 1, 0, 1]):
            self.assertAlmostEqual(actual, expected)

    def test_external_hidden_zero_scale_does_not_reach_local_root(self):
        model = parts()
        changes = {"Outside": {"scale": [0, 1, 1], "children_hidden": True}}
        self.assertEqual(oracle(model, "LeftArm", changes)["visible_cubes"], ["LeftArm:root", "LeftArm:child"])
        self.assertEqual(oracle(model, None, changes)["visible_cubes"], [])

    def test_self_and_descendant_hiding_have_different_geometry(self):
        model = parts()
        self.assertEqual(oracle(model, "LeftArm", {"LeftArm": {"cubes_hidden": True}})["visible_cubes"], ["LeftArm:child"])
        self.assertEqual(oracle(model, "LeftArm", {"LeftArm": {"children_hidden": True}})["visible_cubes"], ["LeftArm:root"])
        self.assertEqual(oracle(model, "LeftArm1", {})["result"], "rejected")


if __name__ == "__main__":
    unittest.main()
