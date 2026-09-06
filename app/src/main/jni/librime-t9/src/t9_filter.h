#ifndef RIME_T9_FILTER_H_
#define RIME_T9_FILTER_H_

#include <string>

#ifndef T9_ALGO_ONLY_BUILD
#include <rime/filter.h>
#include <rime/translation.h>
#include <rime/candidate.h>
#include <rime/common.h>
#endif

namespace rime {

// 将 T9 九键的 preedit 从数字序列转换为拼音显示。
// 移植自 Kotlin T9PreeditConverter.kt 的 convertT9PreeditToPinyin()。
//
// 例如 "54482" + comment "ji gua" → "ji hua"
// "ji'43" + comment "ji kan" → "ji k"
// "5" + comment "le" → "l"
//
// 原 t9_preedit_converter.h 已合并至此，converter 实现在 t9_filter.cc 中。
std::string T9ConvertPreedit(const std::string& preedit,
                              const std::string& comment);

// 候选级 preedit 转换（英文九键适配，2026-08-07）：
//   - comment 为有效拼音（非空且不以 '~' 开头）→ 数字 → 拼音（T9ConvertPreedit）
//     '~' 是 librime 统一编码（unity encoder）后缀标记（如 melt_eng 的 '~s'/'~ed'），
//     非拼音，不应触发数字→拼音转换。
//   - 否则（英文九键方案无拼音注释，如 melt_eng_t9 的 "test"）→ 直接显示候选词文本。
std::string T9ConvertCandidatePreedit(const std::string& preedit,
                                      const std::string& comment,
                                      const std::string& candidate_text);

#ifndef T9_ALGO_ONLY_BUILD
// 包装候选，覆盖 preedit() 返回转换后的拼音，
// 不修改原始候选对象，避免跨 .so 边界的 dynamic_cast 失效问题。
// 继承 SimpleCandidate 而非 Candidate，使得 Lua 绑定的
// dynamic_cast<SimpleCandidate*>(&c) 成功，从而 set_preedit()
// 可被后续 filter（如 super_comment_preedit）通过 cand.preedit = val
// 覆盖 preedit 值，避免锁死 bug。
// 同时实现 genuine() 虚解包协议：GetGenuineCandidate 经此拿到内层
// 原始候选，使长按删词（Memory::OnDeleteEntry）能取到底层 Phrase——
// 之前继承链断在 SimpleCandidate，T9 删词静默无效（全键盘无 t9_filter 不受影响）。
class T9PreeditCandidate : public SimpleCandidate {
public:
    T9PreeditCandidate(an<Candidate> item, const string& preedit)
        : SimpleCandidate(item->type() + "'t9",
                          item->start(), item->end(),
                          item->text(), item->comment(), preedit),
          item_(item) {
        set_quality(item->quality());
    }

    // text()/comment()/preedit() 均继承自 SimpleCandidate（存 item 副本）；
    // set_preedit(v) 供后续 Lua filter（super_comment_preedit）覆盖 preedit。

    an<Candidate> item() const { return item_; }
    an<Candidate> genuine() const override { return item_; }

private:
    an<Candidate> item_;
};

class T9Translation : public Translation {
public:
    T9Translation(an<Translation> translation,
                   char auto_delim,
                   char manual_delim,
                   bool convert_preedit);
    bool Next() override;
    an<Candidate> Peek() override { return cand_; }

private:
    // 定位到下一个可接受的候选。
    void Advance();
    // 转换/缓存当前候选的 preedit（原逻辑）。
    void ConvertCurrent();

    an<Translation> translation_;
    an<Candidate> cand_;
    char auto_delim_;
    char manual_delim_;
    // false（isDisplayOriginalPreedit: true）时透传 preedit，仅缓存 Phrase 码供调频。
    bool convert_preedit_ = false;
};

class T9Filter : public Filter {
public:
    explicit T9Filter(const Ticket& ticket);
    an<Translation> Apply(an<Translation> translation,
                           CandidateList* candidates) override;
private:
    bool convert_preedit_ = false;
    char auto_delimiter_ = ' ';
    char manual_delimiter_ = '\'';
};
#endif  // T9_ALGO_ONLY_BUILD

}  // namespace rime

#endif  // RIME_T9_FILTER_H_
