#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
语言文件清理脚本
功能：
1. 移除其他语言文件中与英文完全相同的翻译条目
2. 移除其他语言文件中英文文件没有的多余条目
3. 保持文件格式整洁
"""

import json
import os
from pathlib import Path

def load_json_file(file_path):
    """加载JSON文件"""
    try:
        with open(file_path, 'r', encoding='utf-8') as f:
            return json.load(f)
    except Exception as e:
        print(f"错误：无法读取文件 {file_path}: {e}")
        return None

def save_json_file(file_path, data):
    """保存JSON文件，保持格式整洁"""
    try:
        with open(file_path, 'w', encoding='utf-8') as f:
            json.dump(data, f, ensure_ascii=False, indent=2, separators=(',', ': '))
        return True
    except Exception as e:
        print(f"错误：无法保存文件 {file_path}: {e}")
        return False

def clean_language_file(en_data, lang_data, lang_name):
    """清理单个语言文件"""
    if not en_data or not lang_data:
        return None

    cleaned_data = {}
    removed_identical = []
    removed_extra = []

    # 遍历语言文件中的所有条目
    for key, value in lang_data.items():
        # 检查英文文件中是否存在该键
        if key not in en_data:
            removed_extra.append(key)
            continue

        # 检查值是否与英文相同
        if value == en_data[key]:
            removed_identical.append(key)
            continue

        # 保留不同的翻译
        cleaned_data[key] = value

    # 打印清理统计
    print(f"\n{lang_name} 清理统计:")
    print(f"  原始条目数: {len(lang_data)}")
    print(f"  清理后条目数: {len(cleaned_data)}")
    print(f"  移除相同翻译: {len(removed_identical)} 个")
    print(f"  移除多余条目: {len(removed_extra)} 个")

    if removed_identical:
        print(f"  相同翻译示例: {removed_identical[:3]}")
    if removed_extra:
        print(f"  多余条目示例: {removed_extra[:3]}")

    return cleaned_data

def main():
    """主函数"""
    # 设置语言文件目录
    lang_dir = Path(__file__).resolve().parents[1] / "src/main/resources/assets/yes_steve_model/lang"

    if not lang_dir.exists():
        print(f"错误：语言文件目录不存在: {lang_dir}")
        return

    # 加载英文基准文件
    en_file = lang_dir / "en_us.json"
    if not en_file.exists():
        print(f"错误：英文基准文件不存在: {en_file}")
        return

    en_data = load_json_file(en_file)
    if not en_data:
        print("错误：无法加载英文基准文件")
        return

    print(f"英文基准文件加载成功，包含 {len(en_data)} 个条目")

    # 获取所有语言文件
    lang_files = list(lang_dir.glob("*.json"))
    lang_files = [f for f in lang_files if f.name != "en_us.json"]

    if not lang_files:
        print("警告：未找到需要清理的语言文件")
        return

    print(f"找到 {len(lang_files)} 个语言文件需要清理")

    # 创建备份目录
    backup_dir = lang_dir / "backup"
    backup_dir.mkdir(exist_ok=True)

    # 清理每个语言文件
    for lang_file in lang_files:
        lang_name = lang_file.stem
        print(f"\n正在处理: {lang_name}")

        # 创建备份
        backup_file = backup_dir / lang_file.name
        try:
            import shutil
            shutil.copy2(lang_file, backup_file)
            print(f"  已备份到: {backup_file}")
        except Exception as e:
            print(f"  备份失败: {e}")
            continue

        # 加载语言文件
        lang_data = load_json_file(lang_file)
        if not lang_data:
            continue

        # 清理语言文件
        cleaned_data = clean_language_file(en_data, lang_data, lang_name)
        if cleaned_data is None:
            continue

        # 如果清理后为空，询问是否删除文件
        if not cleaned_data:
            print(f"  警告: {lang_name} 清理后没有独特翻译，是否删除该文件？")
            response = input("  输入 'y' 删除，其他键保留空文件: ").strip().lower()
            if response == 'y':
                try:
                    lang_file.unlink()
                    print(f"  已删除: {lang_file}")
                except Exception as e:
                    print(f"  删除失败: {e}")
                continue

        # 保存清理后的文件
        if save_json_file(lang_file, cleaned_data):
            print(f"  ✓ 清理完成: {lang_file}")
        else:
            print(f"  ✗ 保存失败: {lang_file}")

    print(f"\n清理完成！备份文件保存在: {backup_dir}")
    print("如需恢复，请从备份目录复制文件")

if __name__ == "__main__":
    main()
