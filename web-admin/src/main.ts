import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
// 组件自带文案（分页的"上一页/下一页"、确认框的取消按钮、表格空数据提示）走语言包：
// 不设 locale 时 Element Plus 默认英文，会在一屏中文里混出 "Cancel" / "Go to previous page"。
// 这不影响功能，但界面上一半中文一半英文很难看——而且只在浏览器里点开才发现，编译期不报错。
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import App from './App.vue'
import router from './router'

createApp(App).use(ElementPlus, { locale: zhCn }).use(router).mount('#app')
