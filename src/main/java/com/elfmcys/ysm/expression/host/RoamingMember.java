package com.elfmcys.ysm.expression.host;

import java.util.HashMap;
import java.util.Map;

/** Closed roaming members used by the builtin compatibility corpus. */
public enum RoamingMember {
    A("a"), B("b"), BACK("back"), BAG_EMOTION("bagemotion"), C("c"), CAR("car"), CAR_CAN_HORN("car_can_horn"),
    CAR_CAN_HORN_1("car_can_horn1"), CAR_GT_SUIT("car_gt_suit"), CAR_HOOD("car_hood"), CAR_HOOD_STATE("car_hood_state"),
    CAR_LEFT_DOOR("car_left_door"), CAR_LEFT_DOOR_STATE("car_left_door_state"), CAR_RIGHT_DOOR("car_right_door"),
    CAR_RIGHT_DOOR_STATE("car_right_door_state"), CAR_TRUNK("car_trunk"), CAR_TRUNK_STATE("car_trunk_state"), D("d"),
    EMOTION("emotion"), END_ROD("end_rod"), EYE("eye"), EYE_STYLE("eyestyle"), F("f"), FACE("face"), G("g"), GUN("gun"),
    H("h"), HAIR_DRESS("hairderss"), HELMET("helmet"), HORN_CHECKER("horn_checker"), HUMU_JING("humujing"),
    IS_COMFORTABLE("is_comfortable"), ISN_COMFORTABLE("isn_comfortable"), MOUTH("mouth"), PLAYER("player"),
    RED_BOW_HEADDRESS("red_bow_headdress"), ROCATE("rocate"), S("s"), SMALL_FOX_SIZE("smallfox_size"),
    UMBRELLA("umbrella"), W("w"), WB("wb"), WHEEL_ROCATE("wheelrocate"), WINGS("wings"), WU("wu"), YUSHE("yushe");

    private static final Map<String, RoamingMember> BY_NAME = index();
    private final String memberName;

    RoamingMember(String memberName) {
        this.memberName = memberName;
    }

    public String memberName() {
        return memberName;
    }

    public static RoamingMember require(String memberName) {
        var value = BY_NAME.get(memberName);
        if (value == null) {
            throw new IllegalArgumentException("unknown roaming member " + memberName);
        }
        return value;
    }

    private static Map<String, RoamingMember> index() {
        var result = new HashMap<String, RoamingMember>();
        for (var value : values()) {
            if (result.put(value.memberName, value) != null) {
                throw new ExceptionInInitializerError("duplicate roaming member " + value.memberName);
            }
        }
        return Map.copyOf(result);
    }
}
