#pragma once

namespace board {
constexpr int xclk = 15;
constexpr int sccbSda = 4;
constexpr int sccbScl = 5;
constexpr int data[] = {11, 9, 8, 10, 12, 18, 17, 16};
constexpr int vsync = 6;
constexpr int href = 7;
constexpr int pclk = 13;
constexpr int pwdn = -1;
constexpr int reset = -1;
constexpr int sdClk = 39;
constexpr int sdCmd = 38;
constexpr int sdD0 = 40;
constexpr int bootButton = 0;
constexpr int ov5640Pid = 0x5640;
}
