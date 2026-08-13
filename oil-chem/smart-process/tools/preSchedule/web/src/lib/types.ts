export interface Comp {
  crude: string;
  load_hr: number;
  is_main: boolean;
}

export interface SeqItem {
  start_h: number;
  dur_h: number;
  end_h: number;
  si: number;
  is_blend: boolean;
  uid: string;
  comps: Comp[];
  rate_hr: number;
  tons: number;
  comp_str: string;
  total_load: number;
}

export interface Score {
  compliant_crudes: number;
  n_batches: number;
  main_switches: number;
  unload_to_t_count: number;
  real_total?: number;
  total_gap?: number;
  benders_iters?: number;
  benders_converged?: boolean;
  is_preferred?: boolean;
  seed?: number;
  round?: number;
  nb?: number;
  obj?: number;
}

export interface RoundData {
  round: number;
  seed: number;
  n_batches: number;
  objective: number;
  status: string;
  score: Score;
  seq: SeqItem[];
  warm_start?: boolean;
  total_gap?: number;
  benders_iters?: number;
  benders_converged?: boolean;
}

export interface GridCell {
  inv: number;
  crude: string | null;
  load: number | null;
  time: number | null;
  proc: number | null;
  recv: number | null;
  unload_recv: number | null;
  transfer_out: number | null;
  no_feed_h: number | null;
}

export interface DailySummary {
  day: number;
  planned_h: number;
  planned_proc: number;
  actual_proc: number;
  actual_time: number;
  gap_proc: number;
  gap_h: number;
  gap_crudes?: { crude: string; gap: number }[];
}

export interface TankGridData {
  round: number;
  grid: Record<string, Record<string, GridCell>>;
  warnings: string[];
  clog: string[];
  unload_to_t_count: number;
  daily_summary: DailySummary[];
}

export interface TankParam {
  cap: number;
  heel: number;
  allow: string;
  avail_cap: number;
  is_g: boolean;
  farness: number;
  crude: string | null;
  ton: number;
}

export interface Parameters {
  year: number;
  month: number;
  days: number;
  proc: Record<string, number>;
  proc_total: number;
  gtanks: string[];
  ttanks: string[];
  tanks: Record<string, TankParam>;
  main_tanks: string[];
  blend_tanks: string[];
  arrivals: { crude: string; ton: number; berth_day: number | null }[];
  recipes: Record<string, { main: string; cap_hr: number; blends: { crude: string; cands: number[] }[] }>;
  rate_hr: Record<string, number>;
  origin: Record<string, string>;
  can_single: Record<string, boolean>;
  global_params: Record<string, number>;
}

export interface Phase0Data {
  ship_assignments: Record<string, { tank: string; is_g: boolean; switch?: boolean; old_crude?: string }>;
  cp_constraints: { crude: string; need_tons: number; deadline_hour: number }[];
  no_process_windows: { crude: string; start_h: number; end_h: number }[];
}

export interface ComplianceDetail {
  crude: string;
  plan: number;
  real: number;
  req: number;
  ok: boolean;
  kind: string;
  origin: string;
}

export interface ComplianceData {
  n_judged: number;
  compliant: number;
  details: ComplianceDetail[];
  proc_total: number;
  plan_total: number;
  real_total: number;
}

export interface ApiData {
  parameters: Parameters;
  phase0: Phase0Data;
  batch_estimate: { lower_bound: number; cap: number; breakdown: Record<string, any> };
  rounds: RoundData[];
  tank_grids: TankGridData[];
  selected_idx: number[];
  compliance: ComplianceData;
  compliance_by_round: ComplianceData[];
  elapsed: number;
  n_rounds_run: number;
  xlsx_files?: string[];
  error?: string;
}
