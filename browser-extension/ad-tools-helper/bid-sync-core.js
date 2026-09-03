/* Pure validation shared by the service worker and regression tests. */
const BidSyncCore = (() => {
  const date = () => new Intl.DateTimeFormat('sv-SE', {timeZone: 'Asia/Shanghai'}).format(new Date());
  function body(day, page) {
    const conditions = {search_field:'promotion_name', search_keyword:'', search_type:'like'};
    for (const key of ['cl_project_id','cl_app_id','user_id','media_account_id','companys','project_id','scene_type','strategy_id','learning_phase','external_action','deep_external_action','material_id']) conditions[key]=[];
    for (const key of ['landing_type','delivery_mode','status_first','ad_type','star_delivery_type','star_task_id','app_type','deep_bid_type','combinatorial_id','status','status_second']) conditions[key]='';
    return {conditions:JSON.stringify(conditions),start_date:day,end_date:day,page,page_size:100,
      sort_field:'stat_cost',sort_direction:'desc',data_type:'list',
      select_kpi_fields:['stat_cost','convert_cnt','conversion_cost','active_register','active_register_cost','cpa_bid','promotion_create_time','account_info','conversion_rate','show_cnt','cpm_platform','click_cnt','ctr','cpc_platform','active_register_rate']};
  }
  function parse(result) {
    if (![0,200,'0','200'].includes(result.code)) throw Error('创量拒绝访问，已暂停。请在创量页面确认登录、账户权限或验证后重试');
    const container = result.data && !Array.isArray(result.data) ? result.data : result;
    const rows = Array.isArray(result.data) ? result.data : container.list ?? container.rows;
    const total = Number(container.total_count ?? result.total_count ?? container.total);
    if (!Array.isArray(rows) || !Number.isSafeInteger(total) || total < 0 || total > 20000)
      throw Error('创量响应列表或总数不符合预期，已暂停，请提供脱敏成功响应');
    return {rows: rows.map(row => {
      const info = row.account_info || {};
      const clean = {promotion_id:row.promotion_id, promotion_name:row.promotion_name ?? '',
        media_account_id:row.media_account_id ?? row.advertiser_id ?? info.media_account_id ?? info.advertiser_id ?? '',
        media_account_name:row.media_account_name ?? row.account_name ?? info.media_account_name ?? info.account_name ?? ''};
      for (const key of ['promotion_id','media_account_id']) {
        if (typeof clean[key] === 'number' && !Number.isSafeInteger(clean[key])) throw Error('接口 ID 数值超出精度范围，请使用导出报表');
        clean[key] = String(clean[key] ?? '');
      }
      if (!/^\d+$/.test(clean.promotion_id)) throw Error('接口缺少计划 ID');
      for (const key of ['stat_cost','convert_cnt','active_register','cpa_bid']) {
        const value=row[key];
        if ((typeof value !== 'number' && typeof value !== 'string') || String(value).trim()==='' || !Number.isFinite(Number(value)) || Number(value)<0)
          throw Error('创量指标缺失或无效: '+key);
        clean[key]=Number(value);
      }
      return clean;
    }), total};
  }
  function append(state, chunk) {
    if (state.total !== null && state.total !== chunk.total) throw Error('分页期间总数变化，请稍后重新启用同步');
    state.total=chunk.total;
    for (const row of chunk.rows) {
      const key=row.media_account_id+':'+row.promotion_id;
      if (state.ids.has(key)) throw Error('分页出现重复计划，未覆盖上次数据');
      state.ids.add(key); state.rows.push(row);
    }
    if (state.rows.length>state.total || (!chunk.rows.length && state.rows.length<state.total)) throw Error('分页数据不完整');
    return state.rows.length===state.total;
  }
  return {date,body,parse,append};
})();
if (typeof module !== 'undefined') module.exports=BidSyncCore;
