/* Pure validation shared by the service worker and regression tests. */
const BidSyncCore = (() => {
  const date = () => new Intl.DateTimeFormat('sv-SE', {timeZone: 'Asia/Shanghai'}).format(new Date());
  function requestId(now=new Date()) {
    return new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hourCycle:'h23'}).format(now).replace(/\D/g,'')+crypto.randomUUID().replaceAll('-','')+'ff';
  }
  function createdRange(day,days=7) {
    if (![7,14,30,90].includes(days)) throw Error('请选择有效的计划创建范围');
    const start=new Date(day+'T00:00:00Z');
    if (!Number.isFinite(start.getTime())) throw Error('统计日期无效');
    start.setUTCDate(start.getUTCDate()-days+1);
    return {createdStart:start.toISOString().slice(0,10),createdEnd:day};
  }
  function body(day, page, days=7, total=null) {
    if (!Number.isInteger(page) || page<1 || page>2) throw Error('仅查询消耗前 200 条，最多 2 页');
    const conditions = {search_field:'promotion_name', search_keyword:'', search_type:'like'};
    for (const key of ['cl_project_id','cl_app_id','user_id','media_account_id','companys','project_id','scene_type','strategy_id','learning_phase','external_action','deep_external_action','deep_bid_type','material_id']) conditions[key]=[];
    for (const key of ['landing_type','delivery_mode','status_first','ad_type','star_delivery_type','star_task_id','app_type','combinatorial_id','status','status_second']) conditions[key]='';
    const range=createdRange(day,days);
    conditions.cdt_start_date=range.createdStart+' 00:00:00';conditions.cdt_end_date=range.createdEnd+' 23:59:59';
    return {conditions:JSON.stringify(conditions),start_date:day,end_date:day,page,page_size:100,
      sort_field:'stat_cost',sort_direction:'desc',data_type:'list',
      ...(total===null?{}:{total_count:total,total_page:Math.ceil(total/100)}),
      select_kpi_fields:['stat_cost','convert_cnt','conversion_cost','active_register','active_register_cost','cpa_bid','promotion_create_time','account_info','conversion_rate','show_cnt','cpm_platform','click_cnt','ctr','cpc_platform','active_register_rate']};
  }
  function parse(result) {
    if (![0,200,'0','200'].includes(result.code)) {
      const code=/^-?\d{1,8}$/.test(String(result.code))?String(result.code):'缺失';
      const trace=/^[a-zA-Z0-9_-]{1,80}$/.test(result.request_id||'')?'；请求编号 '+result.request_id:'';
      throw Error('创量返回业务错误（code='+code+'）'+trace+'，已暂停；请核对原网页响应');
    }
    const container = result.data && !Array.isArray(result.data) ? result.data : result;
    const rows = Array.isArray(result.data) ? result.data : container.list ?? container.rows;
    const total = Number(container.page_info?.total_count ?? container.total_count ?? result.total_count ?? container.total);
    if (!Array.isArray(rows) || rows.length>100 || !Number.isSafeInteger(total) || total < 0)
      throw Error('创量响应列表或总数不符合预期，已暂停，请提供脱敏成功响应');
    return {rows: rows.map(row => {
      const info = row.account_info || {};
      const clean = {promotion_id:row.promotion_id, promotion_name:row.promotion_name ?? '',
        media_account_id:row.media_account_id ?? row.advertiser_id ?? info.media_account_id ?? info.advertiser_id ?? '',
        media_account_name:row.media_account_name ?? row.account_name ?? row.advertiser_nick ?? info.media_account_name ?? info.account_name ?? ''};
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
    const target=Math.min(200,state.total);
    for (const row of chunk.rows) {
      const key=row.media_account_id+':'+row.promotion_id;
      if (state.ids.has(key)) throw Error('分页出现重复计划，未覆盖上次数据');
      state.ids.add(key); state.rows.push(row);
    }
    if (state.rows.length>target || (!chunk.rows.length && state.rows.length<target)) throw Error('消耗前 200 条数据不完整');
    return state.rows.length===target;
  }
  return {date,requestId,createdRange,body,parse,append};
})();
if (typeof module !== 'undefined') module.exports=BidSyncCore;
